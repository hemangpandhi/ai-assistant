package com.tcs.vehicleassistant.hardware.ear

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import com.tcs.vehicleassistant.core.AssistantConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * Session ear: prewarm standby mic + STT, capture utterances, re-arm without
 * destroying the engine. Default: Google STT on GAS, Sherpa Whisper on non-GAS.
 */
class AssistantEar(
    private val context: Context,
) {
    companion object {
        private const val TAG = "AssistantEar"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mic = EarMic(context)

    @Volatile
    private var state: EarState = EarState.Closed

    @Volatile
    private var callbacks: EarSttCallbacks = EarSttCallbacks()

    private var sherpaEngine: SherpaEarSttEngine? = null
    private var googleEngine: GoogleOfflineEarSttEngine? = null
    private var activeEngine: EarSttEngine? = null
    private var usesPcmEngine: Boolean = true

    private var captureJob: Job? = null
    private var startJob: Job? = null

    val currentState: EarState
        get() = state

    fun setCallbacks(callbacks: EarSttCallbacks) {
        this.callbacks = callbacks
        sherpaEngine?.setCallbacks(wrapCallbacks(callbacks))
        googleEngine?.setCallbacks(wrapCallbacks(callbacks))
    }

    /**
     * Allocate standby [EarMic] and load the preferred STT engine.
     * Safe to call on every session show.
     */
    suspend fun prewarm(): Boolean = mutex.withLock {
        if (state == EarState.Armed ||
            state == EarState.Capturing ||
            state == EarState.Finalizing
        ) {
            return true
        }
        state = EarState.Prewarm

        if (!EarMic.hasRecordAudioPermission(context)) {
            Log.w(TAG, "prewarm blocked — RECORD_AUDIO missing")
            state = EarState.Closed
            withContext(Dispatchers.Main) {
                callbacks.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            }
            return false
        }

        // Never silently fall back to Google SpeechRecognizer when Sherpa is preferred:
        // Google STT binds the system Speech Recognition and Synthesis service and,
        // with ear re-arms, continuously grabs/releases the mic.
        val preferGoogle = prefersGoogleEngine()
        val ok = if (preferGoogle) {
            prepareGoogleEngine() || prepareSherpaEngine()
        } else {
            prepareSherpaEngine()
        }

        if (!ok) {
            Log.e(TAG, "prewarm failed — no STT engine ready")
            state = EarState.Closed
            withContext(Dispatchers.Main) {
                callbacks.onError(SherpaEarSttEngine.ERROR_MODELS_MISSING)
            }
            return false
        }

        if (usesPcmEngine) {
            if (!mic.allocateStandby()) {
                Log.e(TAG, "prewarm failed — mic allocate")
                state = EarState.Closed
                withContext(Dispatchers.Main) {
                    callbacks.onError(SpeechRecognizer.ERROR_AUDIO)
                }
                return false
            }
        }

        state = EarState.Armed
        Log.i(TAG, "prewarm complete engine=${if (usesPcmEngine) "sherpa" else "google-offline"}")
        return true
    }

    /**
     * Start capturing one utterance. [force] cancels an in-flight capture and restarts
     * (fixes the silent `isListening` no-op).
     */
    fun startUtterance(force: Boolean = false) {
        startJob?.cancel()
        startJob = scope.launch {
            if (state == EarState.Closed || state == EarState.Prewarm || activeEngine == null) {
                if (!prewarm()) return@launch
            }

            mutex.withLock {
                if (state == EarState.Capturing || state == EarState.Finalizing) {
                    if (!force) {
                        Log.d(TAG, "startUtterance ignored — already $state")
                        return@withLock
                    }
                    stopCaptureLocked()
                }
                if (state != EarState.Armed) {
                    Log.w(TAG, "startUtterance abort — state=$state")
                    return@withLock
                }

                val engine = activeEngine ?: return@withLock

                if (usesPcmEngine) {
                    if (!mic.isAllocated && !mic.allocateStandby()) {
                        withContext(Dispatchers.Main) {
                            callbacks.onError(SpeechRecognizer.ERROR_AUDIO)
                        }
                        return@withLock
                    }
                    if (!mic.startRecording()) {
                        withContext(Dispatchers.Main) {
                            callbacks.onError(SpeechRecognizer.ERROR_AUDIO)
                        }
                        return@withLock
                    }
                    engine.startUtterance()
                    state = EarState.Capturing
                    captureJob = scope.launch { captureLoop(engine as SherpaEarSttEngine) }
                } else {
                    // Release owned mic so platform SpeechRecognizer can open HAL.
                    mic.release()
                    engine.startUtterance()
                    state = EarState.Capturing
                }
            }
        }
    }

    fun stopUtterance() {
        scope.launch {
            mutex.withLock {
                stopCaptureLocked()
            }
        }
    }

    /**
     * Session hide: stop capture and release mic.
     * [releaseEngines] destroys Sherpa/Google so wake-word can reclaim DSP cleanly.
     */
    fun close(releaseEngines: Boolean = true) {
        startJob?.cancel()
        startJob = null
        scope.launch {
            mutex.withLock {
                stopCaptureLocked()
                mic.release()
                if (releaseEngines) {
                    sherpaEngine?.release()
                    sherpaEngine = null
                    googleEngine?.release()
                    googleEngine = null
                    activeEngine = null
                }
                state = EarState.Closed
            }
        }
    }

    fun shutdown() {
        close(releaseEngines = true)
        scope.cancel()
    }

    private suspend fun captureLoop(engine: SherpaEarSttEngine) {
        val frame = FloatArray(EarMic.FRAME_SAMPLES)
        try {
            while (coroutineContext.isActive && state == EarState.Capturing) {
                val read = mic.readFrame(frame)
                if (read > 0) {
                    if (engine.acceptPcmAndShouldEndpoint(frame, read)) {
                        mutex.withLock {
                            if (state != EarState.Capturing) return@withLock
                            state = EarState.Finalizing
                            mic.stopRecording()
                        }
                        // Decode outside the mutex; re-arm after transcript is emitted.
                        engine.finishUtteranceBlocking()
                        mutex.withLock {
                            if (state == EarState.Finalizing) {
                                state = EarState.Armed
                            }
                        }
                        return
                    }
                } else {
                    delay(5)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "captureLoop failed", t)
            withContext(Dispatchers.Main) {
                callbacks.onError(SpeechRecognizer.ERROR_AUDIO)
            }
            mutex.withLock {
                mic.stopRecording()
                if (state == EarState.Capturing || state == EarState.Finalizing) {
                    state = EarState.Armed
                }
            }
        }
    }

    private fun stopCaptureLocked() {
        captureJob?.cancel()
        captureJob = null
        mic.stopRecording()
        // Do not call endUtterance() here — that would decode a cancelled buffer.
        // Google path: stopListening only.
        if (!usesPcmEngine) {
            try {
                activeEngine?.endUtterance()
            } catch (_: Exception) {
            }
        }
        if (state == EarState.Capturing || state == EarState.Finalizing) {
            state = EarState.Armed
        }
    }

    private fun prefersGoogleEngine(): Boolean =
        AssistantConfig.prefersGoogleStt(context)

    private fun prepareSherpaEngine(): Boolean {
        val engine = sherpaEngine ?: SherpaEarSttEngine(context, wrapCallbacks(callbacks)).also {
            sherpaEngine = it
        }
        engine.setCallbacks(wrapCallbacks(callbacks))
        val ok = engine.prepare()
        if (ok) {
            activeEngine = engine
            usesPcmEngine = true
        }
        return ok
    }

    private fun prepareGoogleEngine(): Boolean {
        val engine = googleEngine
            ?: GoogleOfflineEarSttEngine(context, wrapCallbacks(callbacks)).also { googleEngine = it }
        engine.setCallbacks(wrapCallbacks(callbacks))
        val ok = engine.prepare()
        if (ok) {
            activeEngine = engine
            usesPcmEngine = false
        }
        return ok
    }

    /** After Google / Sherpa finals, return to Armed for the next turn. */
    private fun wrapCallbacks(base: EarSttCallbacks): EarSttCallbacks =
        base.copy(
            onResult = { text ->
                if (state == EarState.Capturing || state == EarState.Finalizing) {
                    state = EarState.Armed
                }
                base.onResult(text)
            },
            onEmptyResult = {
                if (state == EarState.Capturing || state == EarState.Finalizing) {
                    state = EarState.Armed
                }
                base.onEmptyResult()
            },
            onError = { code ->
                if (state == EarState.Capturing || state == EarState.Finalizing) {
                    state = EarState.Armed
                }
                base.onError(code)
            },
        )
}

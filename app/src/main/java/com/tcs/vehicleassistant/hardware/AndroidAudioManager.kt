package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.assistant.ui.assistant.api.AssistantDebugLog
import java.util.Locale

class AndroidAudioManager(private val context: Context) : IAudioManager {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null
    private var pendingStartRunnable: Runnable? = null
    private var readyWatchdog: Runnable? = null

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var onTtsStart: ((String) -> Unit)? = null
    private var onTtsDone: ((String) -> Unit)? = null
    private var onTtsError: ((String) -> Unit)? = null
    private var onTtsRangeStart: ((String, Int, Int, Int) -> Unit)? = null

    private var onSttReadyForSpeech: (() -> Unit)? = null
    private var onSttBeginningOfSpeech: (() -> Unit)? = null
    private var onSttEndOfSpeech: (() -> Unit)? = null
    private var onSttResult: ((String) -> Unit)? = null
    private var onSttEmptyResult: (() -> Unit)? = null
    private var onSttError: ((Int) -> Unit)? = null
    private var onSttPartial: ((String) -> Unit)? = null

    /**
     * Idle → Starting (startListening issued) → Listening (ready) → Idle.
     * Soft-stop uses cancel + recreate on next start to avoid ERROR_CLIENT races.
     */
    private enum class SttPhase { Idle, Starting, Listening }
    @Volatile private var sttPhase: SttPhase = SttPhase.Idle

    /** Ignore late callbacks from a destroyed recognizer. */
    private var recognizerEpoch: Int = 0

    /** Next startListening must create a fresh SpeechRecognizer (after cancel/stop). */
    @Volatile private var needsFreshRecognizer: Boolean = false

    /** Earliest uptime when startListening may proceed after a soft stop / BUSY. */
    @Volatile private var earliestStartUptimeMs: Long = 0L

    /** Debounce overlapping startListening from pre-arm + session + retry. */
    @Volatile private var lastStartAttemptUptimeMs: Long = 0L

    /**
     * cancel()/stopListening()/destroy commonly deliver ERROR_CLIENT — that is expected,
     * not a driver-visible failure. Ignore callbacks until this uptime.
     */
    @Volatile private var ignoreClientErrorUntilUptimeMs: Long = 0L

    @Volatile private var consecutiveClientErrors: Int = 0

    @Volatile private var holdingDuck = false
    private var duckFocusRequest: AudioFocusRequest? = null
    private val duckFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        AssistantDebugLog.d("Audio", "duck focus change=$change holding=$holdingDuck")
    }

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
        // Warm STT early — independent of TTS init.
        mainHandler.post { ensureRecognizer() }
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                try {
                    val voices = tts?.voices
                    if (voices != null) {
                        for (voice in voices) {
                            if (voice.locale.language == Locale.US.language && !voice.isNetworkConnectionRequired) {
                                tts?.voice = voice
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                // Match Compose TTS: USAGE_MEDIA is audible on AAOS; USAGE_ASSISTANT often is not.
                try {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                } catch (_: Exception) {
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { onTtsStart?.invoke(it) }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { onTtsDone?.invoke(it) }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { onTtsError?.invoke(it) }
                    }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        utteranceId?.let { onTtsRangeStart?.invoke(it, start, end, frame) }
                    }
                })
                onSuccess()
            } else {
                onError()
            }
        }
    }

    override fun ensureWarmRecognizer() {
        val run = Runnable { ensureRecognizer() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun isActivelyListening(): Boolean =
        sttPhase == SttPhase.Starting ||
            sttPhase == SttPhase.Listening ||
            startPending

    override fun isReadyListening(): Boolean =
        sttPhase == SttPhase.Listening

    @Volatile
    private var endpointingProfile: EndpointingProfile = EndpointingProfile.Default

    /** True while a delayed startListening is queued on the main handler. */
    @Volatile private var startPending: Boolean = false

    override fun startListening() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startListeningOnMain(forceRecreate = false)
        } else {
            // Mark in-flight immediately so callers do not stack a second start.
            startPending = true
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            val run = Runnable {
                pendingStartRunnable = null
                startListeningOnMain(forceRecreate = false)
            }
            pendingStartRunnable = run
            mainHandler.post(run)
        }
    }

    override fun setEndpointingProfile(profile: EndpointingProfile) {
        endpointingProfile = profile
    }

    /**
     * Tear down any recognizer and start clean. Call after ERROR_CLIENT / BUSY.
     * Must be followed by a short delay before the next [startListening] from the caller,
     * or pass [delayedMs] to wait here on the main thread.
     */
    override fun restartListening(delayedMs: Long) {
        val run = Runnable {
            cancelPendingWork()
            ignoreClientErrorsFor(IGNORE_CLIENT_ERROR_MS)
            destroySpeechRecognizerLocked()
            sttPhase = SttPhase.Idle
            needsFreshRecognizer = false
            // RecognitionService stays busy briefly after destroy — always settle.
            val settle = delayedMs.coerceAtLeast(DESTROY_SETTLE_MS)
            earliestStartUptimeMs = SystemClock.uptimeMillis() + settle
            AssistantDebugLog.d("STT", "restartListening delay=${settle}ms")
            // Already destroyed — do not force-recreate again (avoids destroy loop).
            scheduleDeferredStart(settle, forceRecreate = false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun scheduleDeferredStart(delayMs: Long, forceRecreate: Boolean) {
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        startPending = true
        val restart = Runnable {
            pendingRestart = null
            // Keep startPending until startListeningOnMain sets Starting or clears it.
            startListeningOnMain(forceRecreate = forceRecreate)
        }
        pendingRestart = restart
        mainHandler.postDelayed(restart, delayMs)
    }

    private fun startListeningOnMain(forceRecreate: Boolean) {
        if (!forceRecreate && sttPhase == SttPhase.Listening) {
            startPending = false
            AssistantDebugLog.w("STT", "startListening ignored — already Listening")
            return
        }
        if (!forceRecreate && sttPhase == SttPhase.Starting) {
            startPending = false
            AssistantDebugLog.w("STT", "startListening ignored — already Starting")
            return
        }
        val now = SystemClock.uptimeMillis()
        // Collapse bursty callers (pre-arm + requestListen + retry) into one start.
        if (!forceRecreate && now - lastStartAttemptUptimeMs < MIN_START_GAP_MS) {
            val wait = MIN_START_GAP_MS - (now - lastStartAttemptUptimeMs)
            AssistantDebugLog.d("STT", "startListening coalesced — retry in ${wait}ms")
            scheduleDeferredStart(wait, forceRecreate = needsFreshRecognizer)
            return
        }
        if (now < earliestStartUptimeMs) {
            val wait = earliestStartUptimeMs - now
            AssistantDebugLog.d("STT", "startListening delayed ${wait}ms (settle/backoff)")
            scheduleDeferredStart(wait, forceRecreate = forceRecreate || needsFreshRecognizer)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            startPending = false
            AssistantDebugLog.e("STT", "Recognition not available on device")
            sttPhase = SttPhase.Idle
            // Not ERROR_CLIENT — VM must not silent-retry forever.
            onSttError?.invoke(SpeechRecognizer.ERROR_AUDIO)
            return
        }

        // Soft-stop leaves the engine in a bad state for same-instance restart.
        if (forceRecreate || needsFreshRecognizer) {
            destroySpeechRecognizerLocked()
            needsFreshRecognizer = false
            val settle = DESTROY_SETTLE_MS
            earliestStartUptimeMs = SystemClock.uptimeMillis() + settle
            lastStartAttemptUptimeMs = SystemClock.uptimeMillis()
            AssistantDebugLog.d("STT", "post-destroy settle ${settle}ms")
            scheduleDeferredStart(settle, forceRecreate = false)
            return
        }
        ensureRecognizer()
        // Keep media ducked while the recognizer arms (avoids a full pause gap).
        requestAssistantDuck()

        val profile = endpointingProfile
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                profile.completeSilenceMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                profile.possiblyCompleteSilenceMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                profile.minimumLengthMs,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        AssistantDebugLog.d(
            "STT",
            "endpointing=${profile.name} silence=${profile.completeSilenceMs}ms",
        )
        try {
            lastStartAttemptUptimeMs = SystemClock.uptimeMillis()
            startPending = false
            sttPhase = SttPhase.Starting
            AssistantDebugLog.d("STT", "startListening() phase=Starting")
            speechRecognizer?.startListening(intent)
            armReadyWatchdog()
        } catch (t: Throwable) {
            clearReadyWatchdog()
            startPending = false
            sttPhase = SttPhase.Idle
            needsFreshRecognizer = true
            earliestStartUptimeMs = SystemClock.uptimeMillis() + BUSY_BACKOFF_MS
            AssistantDebugLog.e("STT", "startListening failed: ${t.message}")
            onSttError?.invoke(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun armReadyWatchdog() {
        clearReadyWatchdog()
        val epochAtStart = recognizerEpoch
        val watchdog = Runnable {
            if (epochAtStart != recognizerEpoch) return@Runnable
            if (sttPhase != SttPhase.Starting) return@Runnable
            AssistantDebugLog.e("STT", "ready watchdog — stuck Starting epoch=$epochAtStart")
            needsFreshRecognizer = true
            sttPhase = SttPhase.Idle
            destroySpeechRecognizerLocked()
            onSttError?.invoke(SpeechRecognizer.ERROR_CLIENT)
        }
        readyWatchdog = watchdog
        mainHandler.postDelayed(watchdog, READY_WATCHDOG_MS)
    }

    private fun clearReadyWatchdog() {
        readyWatchdog?.let { mainHandler.removeCallbacks(it) }
        readyWatchdog = null
    }

    private fun cancelPendingWork() {
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        pendingRestart = null
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        startPending = false
        clearReadyWatchdog()
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        // applicationContext avoids Service/Activity teardown races (ERROR_CLIENT).
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        val myEpoch = ++recognizerEpoch
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            private fun alive(): Boolean = speechRecognizer != null && myEpoch == recognizerEpoch

            override fun onReadyForSpeech(params: Bundle?) {
                if (!alive()) return
                clearReadyWatchdog()
                consecutiveClientErrors = 0
                sttPhase = SttPhase.Listening
                AssistantDebugLog.d("STT", "ready (phase=Listening) epoch=$myEpoch")
                onSttReadyForSpeech?.invoke()
            }
            override fun onBeginningOfSpeech() {
                if (!alive()) return
                AssistantDebugLog.d("STT", "speech begin")
                onSttBeginningOfSpeech?.invoke()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (!alive()) return
                restoreAssistantMedia()
                AssistantDebugLog.d("STT", "speech end")
                onSttEndOfSpeech?.invoke()
            }
            override fun onError(error: Int) {
                if (!alive()) return
                restoreAssistantMedia()
                clearReadyWatchdog()
                val now = SystemClock.uptimeMillis()
                // Intentional cancel/stop/destroy → ERROR_CLIENT is normal; do not surface.
                if (error == SpeechRecognizer.ERROR_CLIENT &&
                    now < ignoreClientErrorUntilUptimeMs
                ) {
                    sttPhase = SttPhase.Idle
                    needsFreshRecognizer = true
                    AssistantDebugLog.d(
                        "STT",
                        "ERROR_CLIENT ignored (intentional stop/cancel) epoch=$myEpoch",
                    )
                    return
                }
                sttPhase = SttPhase.Idle
                needsFreshRecognizer = true
                val backoff = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY_BACKOFF_MS
                    SpeechRecognizer.ERROR_CLIENT -> CLIENT_BACKOFF_MS
                    else -> POST_STOP_SETTLE_MS
                }
                earliestStartUptimeMs = now + backoff
                val label = sttErrorLabel(error)
                AssistantDebugLog.e(
                    "STT",
                    "onError=$error ($label) epoch=$myEpoch backoff=${backoff}ms",
                )
                if (error == SpeechRecognizer.ERROR_CLIENT) {
                    consecutiveClientErrors++
                    // Let backend own recovery — avoid a second competing restart here.
                } else if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    consecutiveClientErrors = 0
                }
                onSttError?.invoke(error)
            }
            override fun onResults(results: Bundle?) {
                if (!alive()) return
                clearReadyWatchdog()
                sttPhase = SttPhase.Idle
                needsFreshRecognizer = true
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    AssistantDebugLog.d("STT", "result=${matches[0].take(40)}")
                    onSttResult?.invoke(matches[0])
                } else {
                    AssistantDebugLog.w("STT", "empty result")
                    onSttEmptyResult?.invoke()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!alive()) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onSttPartial?.invoke(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        AssistantDebugLog.d("STT", "SpeechRecognizer created epoch=$myEpoch")
    }

    override fun stopListening() {
        val run = Runnable {
            cancelPendingWork()
            ignoreClientErrorsFor(IGNORE_CLIENT_ERROR_MS)
            try {
                // cancel() aborts immediately; it also often delivers ERROR_CLIENT.
                speechRecognizer?.cancel()
            } catch (t: Throwable) {
                AssistantDebugLog.w("STT", "cancel failed: ${t.message}")
            }
            sttPhase = SttPhase.Idle
            needsFreshRecognizer = true
            earliestStartUptimeMs = SystemClock.uptimeMillis() + POST_STOP_SETTLE_MS
            AssistantDebugLog.d("STT", "stop/cancel — next start recreates recognizer")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun destroySpeechRecognizer() {
        val run = Runnable { destroySpeechRecognizerLocked() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun ignoreClientErrorsFor(ms: Long) {
        ignoreClientErrorUntilUptimeMs =
            maxOf(ignoreClientErrorUntilUptimeMs, SystemClock.uptimeMillis() + ms)
    }

    private fun destroySpeechRecognizerLocked() {
        cancelPendingWork()
        ignoreClientErrorsFor(IGNORE_CLIENT_ERROR_MS)
        recognizerEpoch++
        val recognizer = speechRecognizer ?: run {
            sttPhase = SttPhase.Idle
            return
        }
        speechRecognizer = null
        sttPhase = SttPhase.Idle
        // Service-side teardown lags destroy(); callers must honor earliestStartUptimeMs.
        earliestStartUptimeMs = SystemClock.uptimeMillis() + DESTROY_SETTLE_MS
        try {
            // Prefer cancel before destroy so the service tears down cleanly.
            recognizer.cancel()
        } catch (_: Throwable) {
        }
        try {
            recognizer.setRecognitionListener(null)
        } catch (_: Throwable) {
        }
        try {
            recognizer.destroy()
            AssistantDebugLog.d("STT", "recognizer destroyed")
        } catch (t: Throwable) {
            AssistantDebugLog.w("STT", "destroy failed: ${t.message}")
        }
    }

    override fun requestAssistantDuck() {
        val run = Runnable { requestAssistantDuckLocked() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun abandonAssistantDuck() {
        val run = Runnable { abandonAssistantDuckLocked() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun requestAssistantDuckLocked() {
        try {
            if (holdingDuck) {
                AssistantDebugLog.d("Audio", "requestDuck skip — already holding")
                return
            }
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            holdingDuck = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    // Navigation guidance ducks media on AAOS without fully pausing it.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(duckFocusListener, mainHandler)
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .build()
                duckFocusRequest = req
                val result = am.requestAudioFocus(req)
                AssistantDebugLog.d("Audio", "requestDuck result=$result")
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    duckFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                AssistantDebugLog.d("Audio", "requestDuck(legacy) result=$result")
            }
        } catch (t: Throwable) {
            AssistantDebugLog.w("Audio", "requestDuck failed: ${t.message}")
        }
    }

    private fun abandonAssistantDuckLocked() {
        holdingDuck = false
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                duckFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                duckFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(duckFocusListener)
            }
            AssistantDebugLog.d("Audio", "abandonDuck")
        } catch (t: Throwable) {
            AssistantDebugLog.w("Audio", "abandonDuck failed: ${t.message}")
        }
    }

    /**
     * SpeechRecognizer often mutes/pauses music. Unmute the stream, then re-assert
     * duck focus so media stays soft for the rest of the assistant session.
     */
    private fun restoreAssistantMedia() {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {
        }
        if (holdingDuck) {
            requestAssistantDuckLocked()
        }
    }

    override fun speak(text: String, utteranceId: String) {
        try {
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (result == TextToSpeech.ERROR || tts == null) {
                initialize(
                    onSuccess = {
                        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
                    },
                    onError = {
                        onTtsError?.invoke(utteranceId)
                    },
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("AndroidAudioManager", "speak failed", t)
        }
    }

    override fun playSilentUtterance(durationMs: Long, utteranceId: String) {
        try {
            val result = tts?.playSilentUtterance(durationMs, TextToSpeech.QUEUE_ADD, utteranceId)
            if (result == TextToSpeech.ERROR || tts == null) {
                initialize(
                    onSuccess = {
                        tts?.playSilentUtterance(durationMs, TextToSpeech.QUEUE_ADD, utteranceId)
                    },
                    onError = {
                        onTtsError?.invoke(utteranceId)
                    },
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("AndroidAudioManager", "playSilentUtterance failed", t)
        }
    }

    override fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            android.util.Log.w("AndroidAudioManager", "stopSpeaking failed", t)
        }
    }

    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            android.util.Log.w("AndroidAudioManager", "TTS shutdown failed", t)
        }
        tts = null
        abandonAssistantDuckLocked()
        destroySpeechRecognizer()
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit,
    ) {
        this.onTtsStart = onStart
        this.onTtsDone = onDone
        this.onTtsError = onError
        this.onTtsRangeStart = onRangeStart
    }

    override fun setRecognitionListener(
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onEndOfSpeech: () -> Unit,
        onResult: (String) -> Unit,
        onEmptyResult: () -> Unit,
        onError: (Int) -> Unit,
        onPartial: (String) -> Unit,
    ) {
        this.onSttReadyForSpeech = onReadyForSpeech
        this.onSttBeginningOfSpeech = onBeginningOfSpeech
        this.onSttEndOfSpeech = onEndOfSpeech
        this.onSttResult = onResult
        this.onSttEmptyResult = onEmptyResult
        this.onSttError = onError
        this.onSttPartial = onPartial
    }

    companion object {
        private const val POST_STOP_SETTLE_MS = 220L
        private const val DESTROY_SETTLE_MS = 400L
        private const val BUSY_BACKOFF_MS = 900L
        private const val CLIENT_BACKOFF_MS = 550L
        private const val MIN_START_GAP_MS = 450L
        private const val READY_WATCHDOG_MS = 2500L
        /** Window where ERROR_CLIENT from cancel/stop/destroy is ignored. */
        private const val IGNORE_CLIENT_ERROR_MS = 600L

        fun sttErrorLabel(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            else -> "ERROR_UNKNOWN"
        }
    }
}

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

    /** Idle → Starting (startListening issued) → Listening (ready) → Idle. */
    private enum class SttPhase { Idle, Starting, Listening }
    @Volatile private var sttPhase: SttPhase = SttPhase.Idle

    @Volatile private var holdingDuck = false
    private var duckFocusRequest: AudioFocusRequest? = null
    private val duckFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        AssistantDebugLog.d("Audio", "duck focus change=$change holding=$holdingDuck")
    }

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
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

    override fun startListening() {
        val run = Runnable { startListeningOnMain(forceRecreate = false) }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    /**
     * Tear down any recognizer and start clean. Call after ERROR_CLIENT / BUSY.
     * Must be followed by a short delay before the next [startListening] from the caller,
     * or pass [delayedMs] to wait here on the main thread.
     */
    override fun restartListening(delayedMs: Long) {
        val run = Runnable {
            destroySpeechRecognizerLocked()
            sttPhase = SttPhase.Idle
            if (delayedMs <= 0L) {
                startListeningOnMain(forceRecreate = true)
            } else {
                AssistantDebugLog.d("STT", "restartListening delay=${delayedMs}ms")
                val restart = Runnable { startListeningOnMain(forceRecreate = true) }
                pendingRestart = restart
                mainHandler.postDelayed(restart, delayedMs)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun startListeningOnMain(forceRecreate: Boolean) {
        if (!forceRecreate && (sttPhase == SttPhase.Starting || sttPhase == SttPhase.Listening)) {
            AssistantDebugLog.w("STT", "startListening ignored — phase=$sttPhase")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            AssistantDebugLog.e("STT", "Recognition not available on device")
            sttPhase = SttPhase.Idle
            onSttError?.invoke(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        if (forceRecreate) {
            destroySpeechRecognizer()
        }
        ensureRecognizer()
        // Keep media ducked while the recognizer arms (avoids a full pause gap).
        requestAssistantDuck()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            sttPhase = SttPhase.Starting
            AssistantDebugLog.d("STT", "startListening() phase=Starting")
            speechRecognizer?.startListening(intent)
        } catch (t: Throwable) {
            sttPhase = SttPhase.Idle
            AssistantDebugLog.e("STT", "startListening failed: ${t.message}")
            onSttError?.invoke(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        // applicationContext avoids Service/Activity teardown races (ERROR_CLIENT).
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                sttPhase = SttPhase.Listening
                AssistantDebugLog.d("STT", "ready (phase=Listening)")
                onSttReadyForSpeech?.invoke()
            }
            override fun onBeginningOfSpeech() {
                AssistantDebugLog.d("STT", "speech begin")
                onSttBeginningOfSpeech?.invoke()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                restoreAssistantMedia()
                AssistantDebugLog.d("STT", "speech end")
                onSttEndOfSpeech?.invoke()
            }
            override fun onError(error: Int) {
                restoreAssistantMedia()
                sttPhase = SttPhase.Idle
                val label = sttErrorLabel(error)
                AssistantDebugLog.e("STT", "onError=$error ($label)")
                onSttError?.invoke(error)
            }
            override fun onResults(results: Bundle?) {
                sttPhase = SttPhase.Idle
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
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onSttPartial?.invoke(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        AssistantDebugLog.d("STT", "SpeechRecognizer created")
    }

    override fun stopListening() {
        val run = Runnable {
            try {
                speechRecognizer?.stopListening()
            } catch (t: Throwable) {
                AssistantDebugLog.w("STT", "stopListening failed: ${t.message}")
            }
            // stopListening alone often leaves the engine in a bad state for a quick restart.
            sttPhase = SttPhase.Idle
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun destroySpeechRecognizer() {
        val run = Runnable { destroySpeechRecognizerLocked() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun destroySpeechRecognizerLocked() {
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        pendingRestart = null
        val recognizer = speechRecognizer ?: run {
            sttPhase = SttPhase.Idle
            return
        }
        speechRecognizer = null
        sttPhase = SttPhase.Idle
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

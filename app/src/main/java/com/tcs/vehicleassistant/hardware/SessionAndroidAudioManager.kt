package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.util.Log
import com.tcs.vehicleassistant.hardware.ear.AssistantEar
import com.tcs.vehicleassistant.hardware.ear.EarSttCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Session audio facade: TTS still via master [AndroidAudioManager]; mic→text via
 * [AssistantEar] (standby [android.media.AudioRecord] + Sherpa / Google-offline adapter).
 *
 * Additive parallel to master [AndroidAudioManager] — do not edit that class.
 */
class SessionAndroidAudioManager(
    context: Context,
) : SessionAudioPort {

    companion object {
        private const val TAG = "SessionAndroidAudio"
    }

    private val ttsDelegate = AndroidAudioManager(context)
    private val ear = AssistantEar(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var listeningRequested = false

    init {
        // Default no-op callbacks until ViewModel wires setRecognitionListener.
        ear.setCallbacks(EarSttCallbacks())
    }

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
        ttsDelegate.initialize(
            onSuccess = {
                // Warm ear in the background so first listen skips HAL allocate latency.
                prewarmEar()
                onSuccess()
            },
            onError = onError,
        )
    }

    override fun prewarmEar() {
        scope.launch(Dispatchers.IO) {
            val ok = ear.prewarm()
            Log.i(TAG, "prewarmEar ok=$ok state=${ear.currentState}")
        }
    }

    override fun startListening() {
        listeningRequested = true
        ear.startUtterance(force = false)
    }

    override fun startListeningForced() {
        listeningRequested = true
        ear.startUtterance(force = true)
    }

    override fun stopListening() {
        listeningRequested = false
        ear.stopUtterance()
    }

    override fun destroySpeechRecognizer() {
        listeningRequested = false
        // Release mic + engines so wake-word can reclaim the HAL.
        ear.close(releaseEngines = true)
    }

    override fun speak(text: String, utteranceId: String) {
        ttsDelegate.speak(text, utteranceId)
    }

    override fun reloadTtsFromPrefs() {
        ttsDelegate.reloadTtsFromPrefs()
    }

    override fun playSilentUtterance(durationMs: Long, utteranceId: String) {
        ttsDelegate.playSilentUtterance(durationMs, utteranceId)
    }

    override fun stopSpeaking() {
        ttsDelegate.stopSpeaking()
    }

    override suspend fun waitUntilFinishedSpeaking() {
        ttsDelegate.waitUntilFinishedSpeaking()
    }

    override fun shutdown() {
        listeningRequested = false
        ear.shutdown()
        ttsDelegate.shutdown()
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit,
    ) {
        ttsDelegate.setUtteranceListener(onStart, onDone, onError, onRangeStart)
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
        ear.setCallbacks(
            EarSttCallbacks(
                onReadyForSpeech = onReadyForSpeech,
                onBeginningOfSpeech = onBeginningOfSpeech,
                onEndOfSpeech = {
                    listeningRequested = false
                    onEndOfSpeech()
                },
                onResult = { text ->
                    listeningRequested = false
                    onResult(text)
                },
                onEmptyResult = {
                    listeningRequested = false
                    onEmptyResult()
                },
                onError = { code ->
                    listeningRequested = false
                    onError(code)
                },
                onPartial = onPartial,
            ),
        )
    }
}

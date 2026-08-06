package com.tcs.vehicleassistant.hardware.ear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.tcs.vehicleassistant.LatencyLogger
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.VisionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * GAS / platform adapter: Google [SpeechRecognizer] (offline preferred).
 * Default on GAS devices. Does not consume PCM from [EarMic] — the ear must release the mic first.
 */
class GoogleOfflineEarSttEngine(
    private val context: Context,
    private var callbacks: EarSttCallbacks = EarSttCallbacks(),
) : EarSttEngine {

    companion object {
        private const val TAG = "GoogleOfflineEar"
    }

    private var recognizer: SpeechRecognizer? = null
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)

    fun setCallbacks(callbacks: EarSttCallbacks) {
        this.callbacks = callbacks
    }

    override fun isReady(): Boolean =
        AssistantConfig.resolveGoogleRecognitionService(context) != null

    override fun prepare(): Boolean {
        if (!isReady()) {
            Log.w(TAG, "Google RecognitionService not available (non-GAS or missing)")
            return false
        }
        return true
    }

    override fun startUtterance() {
        mainScope.launch {
            ensureRecognizer()
            // Do NOT set EXTRA_PREFER_OFFLINE. On Tangorpro AAOS, Google TTS SODA
            // offline init fails with ConfigStatus 5 → SpeechRecognizer ERROR_CLIENT (5)
            // and never produces transcripts (seen in adb logcat). Online/network ASR works.
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            try {
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "startListening failed", e)
                callbacks.onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun onPcm(frame: FloatArray) = Unit

    override fun endUtterance() {
        mainScope.launch {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "stopListening failed", e)
            }
        }
    }

    override fun release() {
        mainScope.launch {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroy failed", e)
            }
            recognizer = null
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val component = AssistantConfig.resolveGoogleRecognitionService(context)
        if (component == null) {
            Log.e(TAG, "No Google RecognitionService component")
            callbacks.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        // Bind Google explicitly — default createSpeechRecognizer can hit our VIS stub.
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, component).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    callbacks.onReadyForSpeech()
                }

                override fun onBeginningOfSpeech() {
                    LatencyLogger.log("STT", "Beginning of speech (Google offline)")
                    callbacks.onBeginningOfSpeech()
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    LatencyLogger.log("STT", "End of speech (Google offline)")
                    callbacks.onEndOfSpeech()
                }

                override fun onError(error: Int) {
                    Log.e(TAG, "SpeechRecognizer error: $error")
                    callbacks.onError(error)
                }

                override fun onResults(results: Bundle?) {
                    val matches =
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull().orEmpty()
                    if (best.isNotBlank()) {
                        val speaker = VisionState.recognizedUser
                        val text = if (speaker.isNullOrBlank()) best else "[Seat: $speaker] $best"
                        callbacks.onResult(text)
                    } else {
                        callbacks.onEmptyResult()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches =
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { callbacks.onPartial(it) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }
}

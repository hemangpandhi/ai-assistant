package com.tcs.vehicleassistant.hardware.ear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.tcs.vehicleassistant.LatencyLogger
import com.tcs.vehicleassistant.vision.VisionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Legacy GAS / platform adapter. Demoted from the happy path: uses
 * [SpeechRecognizer] with [RecognizerIntent.EXTRA_PREFER_OFFLINE] when available.
 * Does not consume PCM from [EarMic] — the ear must release the mic first.
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
        SpeechRecognizer.isRecognitionAvailable(context)

    override fun prepare(): Boolean {
        if (!isReady()) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return false
        }
        return true
    }

    override fun startUtterance() {
        mainScope.launch {
            ensureRecognizer()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
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
                        callbacks.onResult("[Seat: $speaker] $best")
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

package com.tcs.vehicleassistant.speech

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Minimal [RecognitionService] required by [android.service.voice.VoiceInteractionService]
 * XML (`android:recognitionService`). Cabin STT/TTS uses Sherpa-ONNX via
 * [com.tcs.vehicleassistant.hardware.AndroidAudioManager]; this stub only satisfies the
 * platform contract and fails cleanly for external [android.speech.SpeechRecognizer] clients.
 */
class StubRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening: stub — cabin STT uses Sherpa via AndroidAudioManager")
        reportUnsupported(listener)
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel")
        // No active recognition session to tear down.
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening")
        reportUnsupported(listener)
    }

    private fun reportUnsupported(listener: Callback?) {
        if (listener == null) return
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deliver error to RecognitionService.Callback", e)
        }
    }

    companion object {
        private const val TAG = "StubRecognitionService"
    }
}

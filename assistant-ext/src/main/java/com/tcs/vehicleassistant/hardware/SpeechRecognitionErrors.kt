package com.tcs.vehicleassistant.hardware

/**
 * Human-readable SpeechRecognizer / sherpa error labels (UI/UX extension).
 * Extracted from [AndroidAudioManager] so ViewModel / logs do not depend on the impl class.
 */
object SpeechRecognitionErrors {
    fun label(error: Int): String = when (error) {
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        android.speech.SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        android.speech.SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        0 -> "SHERPA_ERROR"
        else -> "ERROR_$error"
    }

    fun isSoftMiss(errorCode: Int): Boolean =
        errorCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
            errorCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    fun isRecoverable(errorCode: Int): Boolean =
        errorCode == android.speech.SpeechRecognizer.ERROR_CLIENT ||
            errorCode == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY

    fun userMessage(errorCode: Int): String {
        val label = label(errorCode)
        return when (errorCode) {
            android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client side error ($errorCode/$label)"
            android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Insufficient permissions ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_NO_MATCH ->
                "No recognition result matched ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "RecognitionService busy ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_SERVER -> "Error from server ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input ($errorCode)"
            else -> "Unknown recognition error ($errorCode/$label)"
        }
    }
}

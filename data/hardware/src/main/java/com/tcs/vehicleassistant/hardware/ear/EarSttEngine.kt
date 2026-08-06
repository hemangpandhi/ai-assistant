package com.tcs.vehicleassistant.hardware.ear

/**
 * Pluggable speech-to-text backend fed by session-owned PCM (or a legacy
 * [android.speech.SpeechRecognizer] adapter that temporarily releases the mic).
 */
interface EarSttEngine {
    /** True when models / recognition service are ready for [startUtterance]. */
    fun isReady(): Boolean

    /** Load models / create recognizer. Idempotent. */
    fun prepare(): Boolean

    /**
     * Begin a new utterance. For PCM engines this only resets VAD/buffers;
     * for Google offline this starts [android.speech.SpeechRecognizer].
     */
    fun startUtterance()

    /** Feed one mono float PCM frame (16 kHz). No-op for non-PCM engines. */
    fun onPcm(frame: FloatArray)

    /** Stop accepting audio for this utterance (caller will await final via callbacks). */
    fun endUtterance()

    /** Release native recognizer resources; mic ownership stays with [EarMic]. */
    fun release()
}

/**
 * Lifecycle callbacks shared with [com.tcs.vehicleassistant.hardware.IAudioManager]
 * recognition listeners.
 */
data class EarSttCallbacks(
    val onReadyForSpeech: () -> Unit = {},
    val onBeginningOfSpeech: () -> Unit = {},
    val onEndOfSpeech: () -> Unit = {},
    val onResult: (String) -> Unit = {},
    val onEmptyResult: () -> Unit = {},
    val onError: (Int) -> Unit = {},
    val onPartial: (String) -> Unit = {},
)

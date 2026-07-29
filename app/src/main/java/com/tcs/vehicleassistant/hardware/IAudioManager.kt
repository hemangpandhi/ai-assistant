package com.tcs.vehicleassistant.hardware

/**
 * Abstracts the Android hardware dependencies (TTS and STT)
 * away from the ViewModel and View logic to enable unit testing and decoupling.
 *
 * Baseline matches `dev/refactor`. Session / TTFR extras live on [SessionAudioPort].
 */
interface IAudioManager {

    /**
     * Initializes the audio hardware.
     */
    fun initialize(onSuccess: () -> Unit, onError: () -> Unit)

    /**
     * Starts listening for voice input.
     */
    fun startListening()

    /**
     * Stops listening for voice input.
     */
    fun stopListening()

    /**
     * Destroys the speech recognizer and releases its resources.
     * Prefer [stopListening] between sessions to keep a warm recognizer.
     */
    fun destroySpeechRecognizer()

    /**
     * Queues a sentence to be spoken by the TTS engine.
     */
    fun speak(text: String, utteranceId: String)

    /**
     * Queues a silent utterance to act as a callback trigger.
     */
    fun playSilentUtterance(durationMs: Long, utteranceId: String)

    /**
     * Stops all current TTS playback.
     */
    fun stopSpeaking()

    /**
     * Suspends until the TTS queue is empty and playback is finished.
     */
    suspend fun waitUntilFinishedSpeaking()

    /**
     * Releases all hardware resources (TTS + SpeechRecognizer).
     */
    fun shutdown()

    /**
     * Sets a listener to receive TTS utterance progress callbacks.
     */
    fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit
    )

    /**
     * Sets a listener to receive the full speech recognition lifecycle.
     *
     * @param onReadyForSpeech   Called when the recognizer is ready for user to speak.
     * @param onBeginningOfSpeech Called when the user starts speaking.
     * @param onEndOfSpeech      Called when the user stops speaking.
     * @param onResult           Called with the final recognized text (non-blank).
     * @param onEmptyResult      Called when recognition returned no match or blank text.
     * @param onError            Called with a raw error code from SpeechRecognizer.
     * @param onPartial          Called with intermediate partial recognition text.
     */
    fun setRecognitionListener(
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onEndOfSpeech: () -> Unit,
        onResult: (String) -> Unit,
        onEmptyResult: () -> Unit,
        onError: (Int) -> Unit,
        onPartial: (String) -> Unit
    )
}

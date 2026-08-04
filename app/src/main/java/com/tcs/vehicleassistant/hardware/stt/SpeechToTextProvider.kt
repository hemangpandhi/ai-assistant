package com.tcs.vehicleassistant.hardware.stt

interface SpeechToTextProvider {
    /**
     * Initializes the speech-to-text engine.
     */
    fun initialize(onSuccess: () -> Unit, onError: (Exception) -> Unit)

    /**
     * Starts listening for speech.
     */
    fun startListening()

    /**
     * Stops listening for speech.
     */
    fun stopListening()

    /**
     * Cancels the current listening session.
     */
    fun cancelListening()

    /**
     * Registers a listener to receive speech recognition events.
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

    /**
     * Releases any resources held by the provider.
     */
    fun destroy()
}

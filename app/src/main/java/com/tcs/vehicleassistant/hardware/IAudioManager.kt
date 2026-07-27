package com.tcs.vehicleassistant.hardware

/**
 * Abstracts the Android hardware dependencies (TextToSpeech and SpeechRecognizer)
 * away from the ViewModel and View logic to enable unit testing and decoupling.
 */
interface IAudioManager {
    
    /**
     * Initializes the audio hardware.
     */
    fun initialize(onSuccess: () -> Unit, onError: () -> Unit)

    /**
     * Create the SpeechRecognizer if missing (no start).
     * Warm at process/service start so the first session only needs [startListening].
     */
    fun ensureWarmRecognizer()

    /** True while STT is Starting or Listening. */
    fun isActivelyListening(): Boolean
    
    /**
     * Starts listening for voice input.
     */
    fun startListening()

    /**
     * Adaptive endpointing (plan Tier 3.11): shorter silence for cabin commands,
     * longer for open questions. Applied on the next [startListening].
     */
    fun setEndpointingProfile(profile: EndpointingProfile)

    /**
     * Destroys any existing recognizer and starts listening again (after optional delay).
     * Use after ERROR_CLIENT / ERROR_RECOGNIZER_BUSY.
     */
    fun restartListening(delayedMs: Long = 0L)

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
     * Duck media (music) for the assistant session — call as soon as the overlay shows.
     * Uses [android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK].
     */
    fun requestAssistantDuck()

    /**
     * Release the assistant duck focus so media returns to full volume.
     */
    fun abandonAssistantDuck()
    
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

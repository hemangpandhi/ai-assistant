package com.tcs.vehicleassistant.hardware

/**
 * Session / TTFR extensions on top of the refactor [IAudioManager] baseline.
 *
 * UI/UX code (mic pre-arm, ducking, adaptive endpointing, restart) depends on this
 * port so [IAudioManager] can track `dev/refactor` without constant method churn.
 */
interface SessionAudioPort : IAudioManager {

    /**
     * Create the SpeechRecognizer / sherpa engine if missing (no start).
     * Warm at process/service start so the first session only needs [startListening].
     */
    fun ensureWarmRecognizer()

    /** True while STT is Starting or Listening. */
    fun isActivelyListening(): Boolean

    /** True only after [onReadyForSpeech] — not merely after startListening was issued. */
    fun isReadyListening(): Boolean

    /**
     * Adaptive endpointing: shorter silence for cabin commands, longer for open questions.
     * Applied on the next [startListening].
     */
    fun setEndpointingProfile(profile: EndpointingProfile)

    /**
     * Destroys any existing recognizer and starts listening again (after optional delay).
     * Use after ERROR_CLIENT / ERROR_RECOGNIZER_BUSY.
     */
    fun restartListening(delayedMs: Long = 0L)

    /**
     * Duck media (music) for the assistant session — call as soon as the overlay shows.
     * Uses [android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK].
     */
    fun requestAssistantDuck()

    /** Release the assistant duck focus so media returns to full volume. */
    fun abandonAssistantDuck()
}

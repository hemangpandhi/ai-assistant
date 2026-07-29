package com.tcs.vehicleassistant.hardware

/**
 * Adaptive STT silence / endpointing profiles for vehicle commands vs open questions.
 * Applied when the recognizer starts (extras cannot change mid-utterance reliably).
 */
enum class EndpointingProfile(
    val completeSilenceMs: Long,
    val possiblyCompleteSilenceMs: Long,
    val minimumLengthMs: Long,
) {
    /** Short HVAC / media / window phrases. */
    ShortCommand(
        completeSilenceMs = 500L,
        possiblyCompleteSilenceMs = 400L,
        minimumLengthMs = 300L,
    ),

    /** Default cabin assistant balance. */
    Default(
        completeSilenceMs = 800L,
        possiblyCompleteSilenceMs = 600L,
        minimumLengthMs = 400L,
    ),

    /** Open questions / longer free-form speech. */
    OpenQuestion(
        completeSilenceMs = 1200L,
        possiblyCompleteSilenceMs = 900L,
        minimumLengthMs = 500L,
    ),
}

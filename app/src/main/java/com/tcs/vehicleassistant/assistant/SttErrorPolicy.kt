package com.tcs.vehicleassistant.assistant

/**
 * How the Compose / immersive backend should treat an [com.tcs.vehicleassistant.controller.AssistantUiState.Error]
 * from STT.
 *
 * Master [com.tcs.vehicleassistant.controller.AssistantViewModel] intentionally leaves
 * no-match / timeout errors on screen for the XML mic-retry affordance. Immersive has
 * no such control, so those must complete the session.
 */
enum class SttErrorPolicy {
    /** Keep overlay open (e.g. missing Whisper sideloads). */
    Hold,

    /** Re-arm the recognizer. */
    Retry,

    /** Show a brief message, then emit SessionComplete. */
    Complete,
}

fun sttErrorPolicyFor(
    errorMessage: String,
    missingModels: Boolean,
    retryCount: Int,
    maxRetries: Int = 2,
): SttErrorPolicy {
    if (missingModels) return SttErrorPolicy.Hold

    val msg = errorMessage.lowercase()
    val recoverable = msg.contains("client") ||
        msg.contains("busy") ||
        msg.contains("unknown recognition") ||
        msg.contains("audio recording") ||
        msg.contains("(5)") ||
        msg.contains("(8)")

    if (recoverable && retryCount < maxRetries) {
        return SttErrorPolicy.Retry
    }

    return SttErrorPolicy.Complete
}

/** Driver-facing copy for common SpeechRecognizer / empty-result strings. */
fun friendlySttErrorMessage(errorMessage: String): String {
    val msg = errorMessage.lowercase()
    return when {
        msg.contains("no recognition") ||
            msg.contains("didn't hear") ||
            msg.contains("no speech") -> "I didn't catch that."
        else -> errorMessage
    }
}

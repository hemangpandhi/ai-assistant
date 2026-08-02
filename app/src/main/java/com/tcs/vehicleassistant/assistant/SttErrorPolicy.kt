package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.core.AssistantConfig

/**
 * How the Compose / immersive backend should treat an [com.tcs.vehicleassistant.controller.AssistantUiState.Error]
 * from STT.
 *
 * Master [com.tcs.vehicleassistant.controller.AssistantViewModel] intentionally leaves
 * no-match / timeout errors on screen for the XML mic-retry affordance. Immersive has
 * no such control, so those must complete the session — but only after the post-launch
 * listen window so a brief cabin glitch does not immediately say "I didn't catch that."
 */
enum class SttErrorPolicy {
    /** Keep overlay open (e.g. missing Whisper sideloads). */
    Hold,

    /** Re-arm the recognizer (may briefly surface the error). */
    Retry,

    /** Re-arm without surfacing copy — still inside the listen window. */
    RetryQuiet,

    /** Show a brief message, then emit SessionComplete. */
    Complete,
}

/** True for empty / timeout / no-match STT failures (driver sees "I didn't catch that."). */
fun isNoSpeechSttError(errorMessage: String): Boolean {
    val msg = errorMessage.lowercase()
    return msg.contains("no recognition") ||
        msg.contains("didn't hear") ||
        msg.contains("no speech")
}

fun sttErrorPolicyFor(
    errorMessage: String,
    missingModels: Boolean,
    retryCount: Int,
    maxRetries: Int = 2,
    listenElapsedMs: Long = Long.MAX_VALUE,
    minListenBeforeNoSpeechMs: Long = AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS,
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

    // Hotword / launch: keep listening up to ~5s before giving up on silence.
    if (isNoSpeechSttError(errorMessage) && listenElapsedMs < minListenBeforeNoSpeechMs) {
        return SttErrorPolicy.RetryQuiet
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

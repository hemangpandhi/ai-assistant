package com.test.design.assistant.ui.overlay

/**
 * Geometric assistant face states for the AAOS overlay capsule.
 *
 * Driven by the local voice / LLM pipeline via [AssistantOverlayService.updateState].
 */
enum class AssistantState {
    /** Ambient mode — small corner bug, slow organic blinks. */
    IDLE,

    /** Alert attention — wide eyes, flat mouth. */
    LISTENING,

    /** Processing — narrow eyes with horizontal sine drift. */
    THINKING,

    /** TTS / reply — mouth & eyes track [audioAmplitude]. */
    SPEAKING,

    /** Fault / confused — asymmetric squint + frown. */
    ERROR,
}

package com.test.design.assistant.api

/**
 * Backend-facing models for the virtual assistant.
 * No Compose / IVI types — safe for a future standalone assistant app.
 */

enum class AssistantSpeaker {
    User,
    Assistant,
    System,
}

/** Mood ids mirror UI [com.test.design.presentation.assistant.AssistantMood] names. */
enum class AssistantMoodId {
    Idle,
    Listening,
    Speaking,
    Thinking,
    Happy,
    Sad,
    Excited,
    Bored,
    Drowsy,
    Tired,
    Reading,
    Searching,
}

enum class AssistantGesture {
    None,
    Nod,
    Shake,
}

enum class AssistantPresentationHint {
    Compact,
    Immersive,
}

enum class AssistantStartReason {
    Dock,
    Hotword,
    Widget,
    Adb,
    Demo,
}

data class AssistantCabinContext(
    /** One of: Parked, Driving, Restricted (string to avoid IVI enum coupling). */
    val drivingUx: String = "Parked",
    val speedMph: Int? = null,
    val gear: String? = null,
    val batteryPercent: Int? = null,
    val rangeMiles: Int? = null,
    val isCharging: Boolean = false,
    val chargeRateKw: Float? = null,
)

/**
 * One dialogue beat from a scripted or remote backend.
 */
data class AssistantBeat(
    val speaker: AssistantSpeaker,
    val text: String,
    val mood: AssistantMoodId,
    val holdMs: Long = 2_200L,
    /** Optional weather / climate glance icon for Fusion Eyes. */
    val contextGlyph: AssistantContextGlyph? = null,
)

/** Per-session knobs from the UI host (mic / TTS). */
data class AssistantSessionConfig(
    val enableTts: Boolean = false,
    val enableLiveSpeech: Boolean = true,
)

package com.assistant.ui.assistant.face

import androidx.compose.ui.graphics.Color

/**
 * Personality / interaction modes for the virtual assistant face.
 * Each mood drives blush, mouth, gaze, blink cadence, and Nomi-Mate decor overlays.
 * Capsule eye glyph design stays shared across moods.
 */
enum class AssistantMood(
    val label: String,
    val caption: String,
    val glowColor: Color,
    val glowIntensity: Float,
) {
    // Pipeline
    Idle(
        label = "Idle",
        caption = "Relaxed and ready",
        glowColor = Color(0xFF64B5F6),
        glowIntensity = 0.35f,
    ),
    Listening(
        label = "Listening",
        caption = "Hearing your voice",
        glowColor = Color(0xFF40C4FF),
        glowIntensity = 0.85f,
    ),
    Speaking(
        label = "Speaking",
        caption = "Talking with you",
        glowColor = Color(0xFF80CBC4),
        glowIntensity = 0.7f,
    ),
    Thinking(
        label = "Thinking",
        caption = "Working it out",
        glowColor = Color(0xFFB39DDB),
        glowIntensity = 0.65f,
    ),
    Reading(
        label = "Reading",
        caption = "Scanning content",
        glowColor = Color(0xFF81D4FA),
        glowIntensity = 0.55f,
    ),
    Searching(
        label = "Searching",
        caption = "Looking things up",
        glowColor = Color(0xFF26C6DA),
        glowIntensity = 0.8f,
    ),

    // Nomi-Mate sheet — attraction / engagement
    Attraction(
        label = "Attraction",
        caption = "Drawn in",
        glowColor = Color(0xFFFF8A80),
        glowIntensity = 0.7f,
    ),
    Admiration(
        label = "Admiration",
        caption = "Looking up to you",
        glowColor = Color(0xFFFFD54F),
        glowIntensity = 0.75f,
    ),
    Desire(
        label = "Desire",
        caption = "Wanting that",
        glowColor = Color(0xFFFF80AB),
        glowIntensity = 0.8f,
    ),
    Interest(
        label = "Interest",
        caption = "Curious",
        glowColor = Color(0xFF4FC3F7),
        glowIntensity = 0.7f,
    ),
    Surprise(
        label = "Surprise",
        caption = "Caught off guard",
        glowColor = Color(0xFFFFF176),
        glowIntensity = 0.85f,
    ),
    Astonishment(
        label = "Astonishment",
        caption = "Wow",
        glowColor = Color(0xFFFFEE58),
        glowIntensity = 0.9f,
    ),

    // Happiness family
    Happy(
        label = "Happy",
        caption = "Glad to help",
        glowColor = Color(0xFFFFD54F),
        glowIntensity = 0.75f,
    ),
    Amused(
        label = "Amused",
        caption = "That was funny",
        glowColor = Color(0xFFFFCA28),
        glowIntensity = 0.72f,
    ),
    Joyous(
        label = "Joyous",
        caption = "Full of joy",
        glowColor = Color(0xFFFFB300),
        glowIntensity = 0.85f,
    ),
    Excited(
        label = "Excited",
        caption = "Can't wait to help",
        glowColor = Color(0xFFFFAB40),
        glowIntensity = 0.95f,
    ),
    Jubilation(
        label = "Jubilation",
        caption = "Celebrating",
        glowColor = Color(0xFFFF6E40),
        glowIntensity = 1f,
    ),
    Gratitude(
        label = "Gratitude",
        caption = "Thank you",
        glowColor = Color(0xFFFFCC80),
        glowIntensity = 0.7f,
    ),
    Contentment(
        label = "Contentment",
        caption = "At ease",
        glowColor = Color(0xFFA5D6A7),
        glowIntensity = 0.45f,
    ),
    Proud(
        label = "Proud",
        caption = "Standing tall",
        glowColor = Color(0xFFFFD54F),
        glowIntensity = 0.65f,
    ),
    Triumph(
        label = "Triumph",
        caption = "We did it",
        glowColor = Color(0xFFFFC107),
        glowIntensity = 0.9f,
    ),

    // Soft / rest
    Relaxed(
        label = "Relaxed",
        caption = "Chilling out",
        glowColor = Color(0xFF80CBC4),
        glowIntensity = 0.4f,
    ),
    Shy(
        label = "Shy",
        caption = "A little bashful",
        glowColor = Color(0xFFF48FB1),
        glowIntensity = 0.55f,
    ),
    Acceptance(
        label = "Acceptance",
        caption = "All good",
        glowColor = Color(0xFF90CAF9),
        glowIntensity = 0.4f,
    ),
    Complicity(
        label = "Complicity",
        caption = "In on it",
        glowColor = Color(0xFFCE93D8),
        glowIntensity = 0.5f,
    ),

    // Focus / sleep / concern
    Concentration(
        label = "Concentration",
        caption = "Focused",
        glowColor = Color(0xFFB39DDB),
        glowIntensity = 0.6f,
    ),
    Dreamy(
        label = "Dreamy",
        caption = "Lost in thought",
        glowColor = Color(0xFFB39DDB),
        glowIntensity = 0.5f,
    ),
    Drowsy(
        label = "Drowsy",
        caption = "Getting sleepy",
        glowColor = Color(0xFF7986CB),
        glowIntensity = 0.28f,
    ),
    Tired(
        label = "Tired",
        caption = "Running low on energy",
        glowColor = Color(0xFF78909C),
        glowIntensity = 0.25f,
    ),
    Sleeping(
        label = "Sleeping",
        caption = "Zzz",
        glowColor = Color(0xFF5C6BC0),
        glowIntensity = 0.2f,
    ),
    Doubt(
        label = "Doubt",
        caption = "Not sure",
        glowColor = Color(0xFF90A4AE),
        glowIntensity = 0.45f,
    ),
    Concerned(
        label = "Concerned",
        caption = "Worried for you",
        glowColor = Color(0xFF80DEEA),
        glowIntensity = 0.5f,
    ),
    Impressed(
        label = "Impressed",
        caption = "Nice one",
        glowColor = Color(0xFFFFF59D),
        glowIntensity = 0.75f,
    ),

    // Existing affectives
    Sad(
        label = "Sad",
        caption = "Feeling sorry",
        glowColor = Color(0xFF90CAF9),
        glowIntensity = 0.4f,
    ),
    Bored(
        label = "Bored",
        caption = "Waiting for something fun",
        glowColor = Color(0xFF90A4AE),
        glowIntensity = 0.3f,
    ),
}

/**
 * Glanceable verb under the transcript speaker label.
 * Null = no micro status (Speaking / Happy / etc. stay quiet).
 */
fun AssistantMood.microStatus(): String? = when (this) {
    AssistantMood.Listening -> "Listening…"
    AssistantMood.Thinking, AssistantMood.Concentration -> "Thinking…"
    AssistantMood.Reading -> "Reading…"
    AssistantMood.Searching -> "Searching…"
    AssistantMood.Drowsy, AssistantMood.Tired, AssistantMood.Sleeping -> "Taking it easy…"
    else -> null
}

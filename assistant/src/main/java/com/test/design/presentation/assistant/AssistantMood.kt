package com.test.design.presentation.assistant

import androidx.compose.ui.graphics.Color

/**
 * Personality / interaction modes for the virtual assistant face.
 * Each mood drives eye shape, mouth curve, blink cadence, and eye glow.
 */
enum class AssistantMood(
    val label: String,
    val caption: String,
    val glowColor: Color,
    val glowIntensity: Float,
) {
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
    Happy(
        label = "Happy",
        caption = "Glad to help",
        glowColor = Color(0xFFFFD54F),
        glowIntensity = 0.75f,
    ),
    Sad(
        label = "Sad",
        caption = "Feeling sorry",
        glowColor = Color(0xFF90CAF9),
        glowIntensity = 0.4f,
    ),
    Excited(
        label = "Excited",
        caption = "Can't wait to help",
        glowColor = Color(0xFFFFAB40),
        glowIntensity = 0.95f,
    ),
    Bored(
        label = "Bored",
        caption = "Waiting for something fun",
        glowColor = Color(0xFF90A4AE),
        glowIntensity = 0.3f,
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
}

/**
 * Glanceable verb under the transcript speaker label.
 * Null = no micro status (Speaking / Happy / etc. stay quiet).
 */
fun AssistantMood.microStatus(): String? = when (this) {
    AssistantMood.Listening -> "Listening…"
    AssistantMood.Thinking -> "Thinking…"
    AssistantMood.Reading -> "Reading…"
    AssistantMood.Searching -> "Searching…"
    AssistantMood.Drowsy, AssistantMood.Tired -> "Taking it easy…"
    else -> null
}

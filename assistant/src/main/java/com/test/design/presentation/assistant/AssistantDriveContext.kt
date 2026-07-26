package com.test.design.presentation.assistant

/**
 * Cabin-agnostic helpers for mood / gaze / gestures.
 * Proactive drive beats live in [com.test.design.presentation.assistant.backend.buildCabinBeats]
 * so this file never imports IVI / vehicle types.
 */

/** Demo / keyword path into Drowsy or Tired — sensor-ready single mood sink. */
internal fun fatigueMoodForText(text: String): AssistantMood? {
    val t = text.lowercase()
    return when {
        listOf("drowsy", "sleepy", "falling asleep", "nodding off").any { t.contains(it) } ->
            AssistantMood.Drowsy
        listOf("tired", "exhausted", "fatigued", "worn out", "I'm beat").any { t.contains(it) } ->
            AssistantMood.Tired
        else -> null
    }
}

/** Moods that count as a completed assistant answer for thumbs feedback. */
internal fun isAnswerMood(mood: AssistantMood): Boolean = when (mood) {
    AssistantMood.Speaking,
    AssistantMood.Happy,
    AssistantMood.Sad,
    AssistantMood.Excited,
    -> true
    else -> false
}

/**
 * Cabin mic zone → gaze. Negative X looks toward the driver (LHD).
 */
internal fun gazeForSpeaker(speaker: DialogueSpeaker): Pair<Float, Float> = when (speaker) {
    DialogueSpeaker.User -> -0.42f to 0.05f
    DialogueSpeaker.Assistant -> 0f to -0.02f
    DialogueSpeaker.System -> 0.08f to 0f
}

internal fun faceGestureForText(text: String): FaceGesture = when {
    isAffirmativeUtterance(text) -> FaceGesture.Nod
    isNegativeUtterance(text) -> FaceGesture.Shake
    else -> FaceGesture.None
}

enum class FaceGesture {
    None,
    Nod,
    Shake,
}

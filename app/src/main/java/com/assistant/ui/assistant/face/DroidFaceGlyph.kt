package com.assistant.ui.assistant.face

import com.assistant.ui.assistant.face.AssistantMood

/**
 * Exact face / status glyphs from the Bugdroid icon pack (6×6).
 */
enum class DroidFaceGlyph {
    // Row 1 — emotions
    Happy,
    Wink,
    SquintSmile,
    Surprised,
    Laughing,
    Cool,

    // Row 2 — stylized
    StarEyes,
    HeartEyes,
    Dizzy,
    Neutral,
    Sleeping,
    Sad,

    // Row 3 — status
    Success,
    Error,
    Alert,
    Help,
    Ring,
    Search,

    // Row 4 — nav / feedback
    ArrowUp,
    ArrowRight,
    ArrowDown,
    ArrowLeft,
    ThumbsUp,
    ThumbsDown,

    // Row 5 — media / system
    Play,
    Chat,
    User,
    Warning,
    Lock,
    Shield,

    // Row 6 — data
    Waveform,
    Settings,
    Signal,
    Dollar,
    Ellipsis,
    Hi,
}

internal fun AssistantMood.toDroidFaceGlyph(): DroidFaceGlyph = when (this) {
    AssistantMood.Idle,
    AssistantMood.Contentment,
    AssistantMood.Relaxed,
    AssistantMood.Acceptance,
    AssistantMood.Complicity,
    -> DroidFaceGlyph.Neutral
    AssistantMood.Listening,
    AssistantMood.Interest,
    AssistantMood.Surprise,
    -> DroidFaceGlyph.Happy
    AssistantMood.Speaking -> DroidFaceGlyph.Laughing
    AssistantMood.Thinking,
    AssistantMood.Concentration,
    AssistantMood.Dreamy,
    -> DroidFaceGlyph.Help
    AssistantMood.Happy,
    AssistantMood.Amused,
    AssistantMood.Joyous,
    AssistantMood.Gratitude,
    AssistantMood.Proud,
    AssistantMood.Shy,
    -> DroidFaceGlyph.SquintSmile
    AssistantMood.Sad,
    AssistantMood.Doubt,
    AssistantMood.Concerned,
    -> DroidFaceGlyph.Sad
    AssistantMood.Excited,
    AssistantMood.Jubilation,
    AssistantMood.Triumph,
    AssistantMood.Attraction,
    AssistantMood.Admiration,
    AssistantMood.Desire,
    AssistantMood.Astonishment,
    AssistantMood.Impressed,
    -> DroidFaceGlyph.StarEyes
    AssistantMood.Bored -> DroidFaceGlyph.Cool
    AssistantMood.Drowsy,
    AssistantMood.Sleeping,
    -> DroidFaceGlyph.Sleeping
    AssistantMood.Tired -> DroidFaceGlyph.Dizzy
    AssistantMood.Reading,
    AssistantMood.Searching,
    -> DroidFaceGlyph.Search
}

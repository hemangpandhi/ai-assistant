package com.test.design.presentation.assistant

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
    AssistantMood.Idle -> DroidFaceGlyph.Neutral
    AssistantMood.Listening -> DroidFaceGlyph.Happy
    AssistantMood.Speaking -> DroidFaceGlyph.Laughing
    AssistantMood.Thinking -> DroidFaceGlyph.Help
    AssistantMood.Happy -> DroidFaceGlyph.SquintSmile
    AssistantMood.Sad -> DroidFaceGlyph.Sad
    AssistantMood.Excited -> DroidFaceGlyph.StarEyes
    AssistantMood.Bored -> DroidFaceGlyph.Cool
    AssistantMood.Drowsy -> DroidFaceGlyph.Sleeping
    AssistantMood.Tired -> DroidFaceGlyph.Dizzy
    AssistantMood.Reading -> DroidFaceGlyph.Search
    AssistantMood.Searching -> DroidFaceGlyph.Search
}

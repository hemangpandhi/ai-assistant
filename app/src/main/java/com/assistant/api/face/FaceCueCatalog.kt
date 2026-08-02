package com.assistant.api.face

/**
 * Host-neutral face-cue vocabulary shared with the LLM (and tool prompts).
 *
 * Slot keys: [LEFT_EYE], [RIGHT_EYE], [MOUTH], [LEFT_ACCENT], [RIGHT_ACCENT].
 * Null / omitted / empty / `"none"` keeps the geometric face part for that slot.
 * Eyes stay geometric unless that eye slot is set (icons fully replace geometry).
 * Accents sit at the top-left / top-right of the face (above the eyes), not center.
 */
object FaceCueCatalog {
    const val LEFT_EYE = "left_eye"
    const val RIGHT_EYE = "right_eye"
    const val MOUTH = "mouth"
    const val LEFT_ACCENT = "left_accent"
    const val RIGHT_ACCENT = "right_accent"

    /** Canonical icon ids the model may assign to any slot. */
    val iconIds: List<String> = listOf(
        // weather
        "rain", "storm", "snow", "cloudy", "sunny",
        // climate
        "thermostat", "ac", "heat", "fan", "defrost",
        // media
        "music", "podcast", "mic",
        // nav / search
        "search", "navigate",
        // accents / playful
        "sparkle", "star", "wave", "heart",
    )

    /**
     * Prompt fragment for system / tool instructions.
     * Emit a single `<face …/>` tag (not spoken). Omit a slot or use `none` for geometry.
     */
    fun llmPromptFragment(): String = buildString {
        appendLine("Face cues (optional, not spoken aloud):")
        appendLine(
            "When a glanceable topic fits, emit exactly one XML tag before your reply:",
        )
        appendLine(
            """<face left_eye="ID|none" right_eye="ID|none" mouth="ID|none" """ +
                """left_accent="ID|none" right_accent="ID|none"/>""",
        )
        appendLine("Slots:")
        appendLine("- left_eye / right_eye: replace that geometric eye (independent).")
        appendLine("- mouth: replace geometric mouth.")
        appendLine("- left_accent / right_accent: top-left / top-right above the eyes.")
        appendLine("Allowed IDs: ${iconIds.joinToString(", ")}")
        appendLine("Clear all cues with: <face/> or omit the tag.")
    }
}

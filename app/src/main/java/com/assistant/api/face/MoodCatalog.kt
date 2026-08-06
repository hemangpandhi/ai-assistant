package com.assistant.api.face

/**
 * Host-neutral affective mood vocabulary for LLM `<mood>` tags.
 * Pipeline phases (idle/listening/thinking/speaking/…) are harness-owned;
 * the model only emits affective ids from conversation context.
 */
object MoodCatalog {
    /** Affective ids the model may emit (case-insensitive). */
    val moodIds: List<String> = listOf(
        "attraction", "admiration", "desire",
        "interest", "surprise", "astonishment",
        "happy", "amused", "joyous", "excited", "jubilation",
        "gratitude", "contentment", "proud", "triumph",
        "relaxed", "shy", "acceptance", "complicity",
        "concentration", "dreamy",
        "drowsy", "tired", "sleeping",
        "doubt", "concerned", "impressed",
        "sad", "bored",
    )

    /**
     * Prompt fragment: choose one silent mood from conversation context.
     * Consumed by [com.assistant.ui.assistant.api.MoodTagParser].
     */
    fun llmPromptFragment(): String = buildString {
        appendLine("Face expression mood (optional, not spoken aloud):")
        appendLine(
            "From the conversation context (user feeling, success, humor, fatigue, surprise), " +
                "emit exactly one silent tag before your spoken reply:",
        )
        appendLine("""<mood>ID</mood>""")
        appendLine("Pick the single best ID for how the assistant face should look right now.")
        appendLine("Allowed IDs: ${moodIds.joinToString(", ")}")
        appendLine("Clear with <mood/> or omit the tag to keep the current face.")
        appendLine("Never speak the mood tag or list these IDs to the driver.")
    }
}

package com.assistant.ui.assistant.api

/**
 * Parses / strips LLM face-cue tags from assistant text.
 *
 * Accepted forms (case-insensitive attribute names):
 * ```
 * <face left_eye="sunny" right_eye="sunny" mouth="music" left_accent="sparkle" right_accent="none"/>
 * <face/>
 * ```
 */
object FaceCueParser {
    private val tagRegex = Regex(
        """<face\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val attrRegex = Regex(
        """(\w+)\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    data class ParseResult(
        /** Text with face tags removed (for TTS / transcript). */
        val cleanedText: String,
        /** Last face tag in the text; null if none. Empty cues when `<face/>`. */
        val cues: AssistantFaceCues?,
        val found: Boolean,
    )

    fun parse(text: String): ParseResult {
        var last: AssistantFaceCues? = null
        var found = false
        tagRegex.findAll(text).forEach { match ->
            found = true
            last = attrsToCues(match.groupValues.getOrNull(1).orEmpty())
        }
        val cleaned = tagRegex.replace(text, "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return ParseResult(cleanedText = cleaned, cues = if (found) last else null, found = found)
    }

    /** True when [text] contains at least one face tag. */
    fun containsTag(text: String): Boolean = tagRegex.containsMatchIn(text)

    private fun attrsToCues(attrs: String): AssistantFaceCues {
        val map = linkedMapOf<String, String>()
        attrRegex.findAll(attrs).forEach { m ->
            map[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        if (map.isEmpty()) return AssistantFaceCues.Empty
        return AssistantFaceCues(
            leftEye = AssistantFaceCueIcon.parse(map["left_eye"]),
            rightEye = AssistantFaceCueIcon.parse(map["right_eye"]),
            mouth = AssistantFaceCueIcon.parse(map["mouth"]),
            leftAccent = AssistantFaceCueIcon.parse(map["left_accent"]),
            rightAccent = AssistantFaceCueIcon.parse(map["right_accent"]),
        )
    }
}

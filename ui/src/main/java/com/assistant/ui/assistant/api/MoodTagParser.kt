package com.assistant.ui.assistant.api

/**
 * Parses / strips LLM mood tags from assistant text.
 *
 * Accepted forms:
 * ```
 * <mood>happy</mood>
 * <mood id="triumph"/>
 * <mood/>
 * ```
 */
object MoodTagParser {
    private val blockRegex = Regex(
        """<mood\b([^>]*)>(.*?)</mood\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val selfClosingRegex = Regex(
        """<mood\b([^>]*)/>""",
        RegexOption.IGNORE_CASE,
    )
    private val attrRegex = Regex(
        """(\w+)\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    data class ParseResult(
        val cleanedText: String,
        /** Last mood id in the text; null if none / cleared. */
        val mood: AssistantMoodId?,
        val found: Boolean,
    )

    fun parse(text: String): ParseResult {
        var last: AssistantMoodId? = null
        var found = false

        blockRegex.findAll(text).forEach { match ->
            found = true
            val fromBody = match.groupValues.getOrNull(2)?.trim().orEmpty()
            val fromAttrs = moodFromAttrs(match.groupValues.getOrNull(1).orEmpty())
            last = parseMoodId(fromBody) ?: fromAttrs
        }

        var working = blockRegex.replace(text, "")
        selfClosingRegex.findAll(working).forEach { match ->
            found = true
            last = moodFromAttrs(match.groupValues.getOrNull(1).orEmpty())
        }
        working = selfClosingRegex.replace(working, "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

        return ParseResult(
            cleanedText = working,
            mood = if (found) last else null,
            found = found,
        )
    }

    fun containsTag(text: String): Boolean =
        blockRegex.containsMatchIn(text) || selfClosingRegex.containsMatchIn(text)

    fun parseMoodId(raw: String?): AssistantMoodId? {
        val key = raw?.trim()?.lowercase().orEmpty()
        if (key.isEmpty() || key == "none" || key == "null" || key == "clear") return null
        return AssistantMoodId.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }

    private fun moodFromAttrs(attrs: String): AssistantMoodId? {
        val map = linkedMapOf<String, String>()
        attrRegex.findAll(attrs).forEach { m ->
            map[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return parseMoodId(map["id"] ?: map["mood"] ?: map["name"])
    }
}

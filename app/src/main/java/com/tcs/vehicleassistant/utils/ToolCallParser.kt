package com.tcs.vehicleassistant.utils

data class ParsedToolCall(
    val fullTag: String,
    val toolName: String,
    val args: String
) {
    /** Canonical tool call string used for execution / dedupe. */
    val invocation: String get() = "$toolName($args)"
}

object ToolCallParser {

    /** Complete tags only — safe for eager mid-stream execution. */
    private val COMPLETE_TOOL_REGEX =
        Regex("(?i)<TOOL>\\s*([a-zA-Z0-9_]+)(?:\\((.*?)\\))?\\s*</TOOL>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Extracts all valid tool calls from the LLM output.
     * We look for the exact format: <TOOL>toolName(args)</TOOL>
     * Malformed or incomplete tags are ignored to prevent execution errors.
     */
    fun extractToolCalls(llmOutput: String): List<ParsedToolCall> {
        return extractCompleteToolCalls(llmOutput)
    }

    /**
     * Only tags with a closing `</TOOL>` — use during streaming so incomplete
     * tags never execute early.
     */
    fun extractCompleteToolCalls(llmOutput: String): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()
        for (match in COMPLETE_TOOL_REGEX.findAll(llmOutput)) {
            val toolName = match.groups[1]?.value?.trim() ?: continue
            val args = match.groups[2]?.value?.trim() ?: ""
            calls.add(ParsedToolCall(match.value, toolName, args))
        }
        return calls
    }

    /**
     * Strips all tool tags (complete and incomplete) from the LLM output,
     * leaving only the conversational text for the TTS engine.
     */
    fun stripToolTags(llmOutput: String): String {
        // First strip complete tags, making the final > optional to catch cut-off generations
        var cleaned = llmOutput.replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>?|$)"), "")

        // Then strip any trailing incomplete tags (e.g., if generation was cut off midway)
        val finalLastTagIndex = cleaned.lastIndexOf("<")
        if (finalLastTagIndex != -1) {
            val potentialTag = cleaned.substring(finalLastTagIndex).uppercase()
            // Catch things like <T, <TO, <TOOL, or </T, </TO
            if (potentialTag.startsWith("<T") || potentialTag.startsWith("</T") ||
                "<TOOL".startsWith(potentialTag) || "</TOOL".startsWith(potentialTag)) {
                cleaned = cleaned.substring(0, finalLastTagIndex)
            }
        }

        return cleaned.trim()
    }
}

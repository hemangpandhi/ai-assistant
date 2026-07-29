package com.tcs.vehicleassistant.utils

data class ParsedToolCall(
    val fullTag: String,
    val toolName: String,
    val args: String
) {
    /** Canonical tool call string used for execution / dedupe. */
    val invocation: String get() = "$toolName($args)"
}

/**
 * Final / fallback tool extraction — kept close to `dev/refactor`.
 *
 * Eager mid-stream (complete-tags-only) parsing lives in [StreamingToolCallParser].
 */
object ToolCallParser {

    /**
     * Extracts all valid tool calls from the LLM output.
     * Prefers complete streaming-safe tags; falls back to bare function-call syntax
     * when the name matches a registered tool.
     */
    fun extractToolCalls(llmOutput: String): List<ParsedToolCall> {
        val complete = extractCompleteToolCalls(llmOutput)
        if (complete.isNotEmpty()) return complete

        val calls = mutableListOf<ParsedToolCall>()
        val funcRegex = Regex("""\b([a-zA-Z0-9_]+)\((.*?)\)""")
        for (match in funcRegex.findAll(llmOutput)) {
            val fullTag = match.value
            val toolName = match.groups[1]?.value?.trim() ?: continue
            val args = match.groups[2]?.value?.trim() ?: ""
            val toolManager = try {
                org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>()
            } catch (_: Exception) {
                null
            }
            if (toolManager?.getToolDefinition(toolName) != null) {
                calls.add(ParsedToolCall(fullTag, toolName, args))
            }
        }
        return calls
    }

    /**
     * Only tags with a closing `</TOOL>` / `</tool_call>` — use during streaming so incomplete
     * tags never execute early. Delegates to [StreamingToolCallParser].
     */
    fun extractCompleteToolCalls(llmOutput: String): List<ParsedToolCall> =
        StreamingToolCallParser.extractCompleteToolCalls(llmOutput)

    /**
     * Strips all tool tags (complete and incomplete) from the LLM output,
     * leaving only the conversational text for the TTS engine.
     */
    fun stripToolTags(llmOutput: String): String {
        var cleaned = llmOutput
            .replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>?|$)"), "")
            .replace(Regex("(?i)<tool_call>[\\s\\S]*?(</tool_call>?|$)"), "")

        val finalLastTagIndex = cleaned.lastIndexOf("<")
        if (finalLastTagIndex != -1) {
            val potentialTag = cleaned.substring(finalLastTagIndex).uppercase()
            if (potentialTag.startsWith("<T") || potentialTag.startsWith("</T") ||
                "<TOOL".startsWith(potentialTag) || "</TOOL".startsWith(potentialTag) ||
                "<TOOL_CALL".startsWith(potentialTag) || "</TOOL_CALL".startsWith(potentialTag)
            ) {
                cleaned = cleaned.substring(0, finalLastTagIndex)
            }
        }

        return cleaned.trim()
    }
}

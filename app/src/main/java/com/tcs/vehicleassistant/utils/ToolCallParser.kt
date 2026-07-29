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

    private val COMPLETE_JSON_TOOL_REGEX =
        Regex("""(?i)<tool_call>\s*\{[\s\S]*?"name"\s*:\s*"([^"]+)"[\s\S]*?\}\s*</tool_call>""")

    /**
     * Extracts all valid tool calls from the LLM output.
     * Prefers complete XML tags; also accepts closed JSON tool_call blocks and
     * bare function-call syntax when the name matches a registered tool (dev/refactor).
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
     * tags never execute early.
     */
    fun extractCompleteToolCalls(llmOutput: String): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()
        for (match in COMPLETE_TOOL_REGEX.findAll(llmOutput)) {
            val toolName = match.groups[1]?.value?.trim() ?: continue
            val args = match.groups[2]?.value?.trim() ?: ""
            calls.add(ParsedToolCall(match.value, toolName, args))
        }
        for (match in COMPLETE_JSON_TOOL_REGEX.findAll(llmOutput)) {
            val fullTag = match.value
            val toolName = match.groups[1]?.value?.trim() ?: continue
            val args = if (fullTag.contains("\"arguments\"")) {
                fullTag.substringAfter("\"arguments\"").substringAfter(":").substringBefore("}")
                    .replace("\"", "").replace("{", "").trim()
            } else {
                ""
            }
            calls.add(ParsedToolCall(fullTag, toolName, args))
        }
        return calls
    }

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

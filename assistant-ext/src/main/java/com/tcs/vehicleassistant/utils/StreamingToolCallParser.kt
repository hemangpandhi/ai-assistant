package com.tcs.vehicleassistant.utils

val ParsedToolCall.invocation: String
    get() = "$toolName($args)"

/**
 * Streaming-safe tool extraction (UI/UX / TTFR extension).
 *
 * Only returns tags with a closing `</TOOL>` / `</tool_call>` so incomplete
 * mid-stream fragments never execute early. Kept separate from [ToolCallParser]
 * so refactor’s final/fallback parsing can change without fighting eager-stream rules.
 */
object StreamingToolCallParser {

    private val COMPLETE_TOOL_REGEX =
        Regex("(?i)<TOOL>\\s*([a-zA-Z0-9_]+)(?:\\((.*?)\\))?\\s*</TOOL>", RegexOption.DOT_MATCHES_ALL)

    private val COMPLETE_JSON_TOOL_REGEX =
        Regex("""(?i)<tool_call>\s*\{[\s\S]*?"name"\s*:\s*"([^"]+)"[\s\S]*?\}\s*</tool_call>""")

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
}

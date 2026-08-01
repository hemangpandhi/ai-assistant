package com.tcs.vehicleassistant.utils

data class ParsedToolCall(
    val fullTag: String,
    val toolName: String,
    val args: String
)

object ToolCallParser {

    /**
     * Extracts all valid tool calls from the LLM output.
     * We look for the exact format: <TOOL>toolName(args)</TOOL>
     * Malformed or incomplete tags are ignored to prevent execution errors.
     */
    fun extractToolCalls(llmOutput: String): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()
        
        // 1. XML Tag Format: <TOOL>toolName(args)</TOOL>
        val xmlRegex = Regex("(?i)<TOOL>\\s*([a-zA-Z0-9_]+)(?:\\((.*?)\\))?\\s*(?:</TOOL>|</TOOL|$)", RegexOption.DOT_MATCHES_ALL)
        for (match in xmlRegex.findAll(llmOutput)) {
            val fullTag = match.value
            val toolName = match.groups[1]?.value?.trim() ?: continue
            val args = match.groups[2]?.value?.trim() ?: ""
            calls.add(ParsedToolCall(fullTag, toolName, args))
        }

        // 2. Native JSON Format: <tool_call>{"name": "toolName", "arguments": {...}}</tool_call>
        val jsonRegex = Regex("""(?i)<tool_call>\s*\{[\s\S]*?"name"\s*:\s*"([^"]+)"[\s\S]*?\}\s*</tool_call>""")
        for (match in jsonRegex.findAll(llmOutput)) {
            val fullTag = match.value
            val toolName = match.groups[1]?.value?.trim() ?: continue
            // Extract raw arguments inside JSON
            val args = if (fullTag.contains("\"arguments\"")) {
                fullTag.substringAfter("\"arguments\"").substringAfter(":").substringBefore("}").replace("\"", "").replace("{", "").trim()
            } else ""
            calls.add(ParsedToolCall(fullTag, toolName, args))
        }
        // 3. Fallback Function Call Syntax: toolName(args) or call:toolName(args) without explicit XML wrapping
        if (calls.isEmpty()) {
            val funcRegex = Regex("""\b([a-zA-Z0-9_]+)\((.*?)\)""")
            for (match in funcRegex.findAll(llmOutput)) {
                val fullTag = match.value
                val toolName = match.groups[1]?.value?.trim() ?: continue
                val args = match.groups[2]?.value?.trim() ?: ""
                val toolManager = try {
                    org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>()
                } catch (_: Exception) { null }
                if (toolManager?.getToolDefinition(toolName) != null) {
                    calls.add(ParsedToolCall(fullTag, toolName, args))
                }
            }
        }

        return calls
    }

    /**
     * Strips all tool tags (complete and incomplete) from the LLM output,
     * leaving only the conversational text for the TTS engine.
     */
    fun stripToolTags(llmOutput: String): String {
        // Strip complete XML and JSON tool tags
        var cleaned = llmOutput
            .replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>?|$)"), "")
            .replace(Regex("(?i)<tool_call>[\\s\\S]*?(</tool_call>?|$)"), "")
        
        // Strip trailing incomplete tags
        val finalLastTagIndex = cleaned.lastIndexOf("<")
        if (finalLastTagIndex != -1) {
            val potentialTag = cleaned.substring(finalLastTagIndex).uppercase()
            if (potentialTag.startsWith("<T") || potentialTag.startsWith("</T") || 
                "<TOOL".startsWith(potentialTag) || "</TOOL".startsWith(potentialTag) ||
                "<TOOL_CALL".startsWith(potentialTag) || "</TOOL_CALL".startsWith(potentialTag)) {
                cleaned = cleaned.substring(0, finalLastTagIndex)
            }
        }
        
        return cleaned.trim()
    }
}

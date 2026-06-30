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
        // Handle </TOOL>, </TOOL (missing >), or end of string if cut off
        val regex = Regex("(?i)<TOOL>\\s*([a-zA-Z0-9_]+)(?:\\((.*?)\\))?\\s*(?:</TOOL>|</TOOL|$)", RegexOption.DOT_MATCHES_ALL)
        
        val matches = regex.findAll(llmOutput)
        for (match in matches) {
            val fullTag = match.value
            val toolName = match.groups[1]?.value?.trim() ?: continue
            val args = match.groups[2]?.value?.trim() ?: ""
            calls.add(ParsedToolCall(fullTag, toolName, args))
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

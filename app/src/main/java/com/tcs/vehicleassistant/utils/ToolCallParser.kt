package com.tcs.vehicleassistant.utils



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
        val jsonRegex = Regex("""(?i)<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        for (match in jsonRegex.findAll(llmOutput)) {
            val fullTag = match.value
            val jsonStr = match.groups[1]?.value?.trim() ?: continue
            try {
                val jsonObj = org.json.JSONObject(jsonStr)
                val toolName = jsonObj.optString("name")
                if (toolName.isEmpty()) continue
                
                var args = ""
                val argumentsObj = jsonObj.optJSONObject("arguments")
                if (argumentsObj != null) {
                    val keys = argumentsObj.keys()
                    if (keys.hasNext()) {
                        args = argumentsObj.optString(keys.next())
                    }
                } else {
                    args = jsonObj.optString("arguments", "")
                }
                
                calls.add(ParsedToolCall(fullTag, toolName, args))
            } catch (e: Exception) {
                // Ignore malformed JSON
            }
        }
        // 3. Fallback Function Call Syntax: toolName(args) or call:toolName(args) without explicit XML wrapping
        if (calls.isEmpty()) {
            val funcRegex = Regex("""\b([a-zA-Z0-9_]+)\((.*?)\)""")
            for (match in funcRegex.findAll(llmOutput)) {
                val fullTag = match.value
                val toolName = match.groups[1]?.value?.trim() ?: continue
                val args = match.groups[2]?.value?.trim() ?: ""
                val toolRegistry = try {
                    org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolRegistry>()
                } catch (_: Exception) { null }
                if (toolRegistry?.getToolDefinition(toolName) != null) {
                    calls.add(ParsedToolCall(fullTag, toolName, args))
                }
            }
        }

        return calls
    }

    fun stripToolTags(llmOutput: String): String {
        var cleaned = llmOutput
        
        // Strip complete XML, JSON tool tags, and fallback function syntaxes
        val parsedCalls = extractToolCalls(llmOutput)
        for (call in parsedCalls) {
            cleaned = cleaned.replace(call.fullTag, "")
        }
        
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

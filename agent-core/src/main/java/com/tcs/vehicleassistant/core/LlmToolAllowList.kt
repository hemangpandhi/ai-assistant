package com.tcs.vehicleassistant.core

/**
 * Per-turn allow-list for LLM-proposed tool calls.
 *
 * The model only sees up to [ToolManager.maxPromptTools] tools each turn. Executing any other
 * registry tool (hallucinated or from stale KV memory) is rejected before VHAL/handler work.
 */
object LlmToolAllowList {

    private val ALLOWED_LINE = Regex("""(?im)^Allowed tools:\s*(.+)$""")
    private val TOOL_TAG = Regex("""(?i)<TOOL>\s*([a-zA-Z0-9_]+)\s*\(""")

    /** Parses tool names from a [ToolManager.getLlmToolsPrompt] block. */
    fun extractAllowedToolNames(toolsPrompt: String): Set<String> {
        if (toolsPrompt.isBlank()) return emptySet()
        val names = linkedSetOf<String>()
        ALLOWED_LINE.find(toolsPrompt)?.groupValues?.getOrNull(1)?.let { line ->
            line.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { names += it }
        }
        for (match in TOOL_TAG.findAll(toolsPrompt)) {
            match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { names += it }
        }
        return names
    }

    /**
     * @param toolName handler or alias from the model
     * @param allowedKeys names injected this turn
     * @param canonicalKey optional resolution of [toolName] to registry handler key
     */
    fun isAllowed(
        toolName: String,
        allowedKeys: Set<String>,
        canonicalKey: String? = null,
    ): Boolean {
        if (allowedKeys.isEmpty()) return false
        val name = toolName.trim()
        if (name.isEmpty()) return false
        if (allowedKeys.any { it.equals(name, ignoreCase = true) }) return true
        val canonical = canonicalKey?.trim().orEmpty()
        return canonical.isNotEmpty() && allowedKeys.any { it.equals(canonical, ignoreCase = true) }
    }

    fun rejectionMessage(toolName: String): String =
        "System Error: Tool `$toolName` was not offered for this turn."
}

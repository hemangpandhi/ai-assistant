package com.tcs.vehicleassistant.core

/**
 * Builds registry-owned few-shot lines for the local LiteRT system prompt so examples
 * cannot drift from tools that actually exist in `vehicle_skills_registry.json`.
 */
object LocalLlmPromptSupport {

    data class FewShot(val user: String, val assistant: String)

    fun formatFewShots(shots: List<FewShot>): String {
        if (shots.isEmpty()) return ""
        val sb = StringBuilder("=== FEW-SHOT EXAMPLES ===\n")
        for (shot in shots) {
            val user = shot.user.trim()
            val assistant = shot.assistant.trim()
            if (user.isEmpty() || assistant.isEmpty()) continue
            sb.append("User: ").append(user).append('\n')
            sb.append("Assistant: ").append(assistant).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n\n"
    }

    /**
     * Keeps only few-shots whose assistant line references a tool that is present in [availableToolKeys].
     * Bare conversational examples (no `<TOOL>`) always pass.
     */
    fun filterByAvailableTools(shots: List<FewShot>, availableToolKeys: Set<String>): List<FewShot> {
        if (availableToolKeys.isEmpty()) return shots
        return shots.filter { shot ->
            val tool = extractToolKey(shot.assistant) ?: return@filter true
            availableToolKeys.any { it.equals(tool, ignoreCase = true) }
        }
    }

    fun extractToolKey(assistantLine: String): String? {
        val inner = assistantLine
            .substringAfter("<TOOL>", missingDelimiterValue = "")
            .substringBefore("</TOOL>", missingDelimiterValue = "")
            .substringBefore("(")
            .trim()
        return inner.takeIf { it.isNotEmpty() }
    }
}

package com.tcs.vehicleassistant.domain

/**
 * TTS utterance generation ids for barge-in / stale-completion rejection
 * (UI/UX extension extracted from AgentOrchestrator).
 */
class TtsTurnIds {
    @Volatile
    private var generation: Int = 0

    fun advance(): Int {
        generation++
        return generation
    }

    fun current(): Int = generation

    fun id(kind: String): String = "${kind}_$generation"

    /**
     * @return the kind prefix if [utteranceId] matches the current generation, else null.
     */
    fun match(utteranceId: String): String? {
        val kind = when {
            utteranceId.startsWith("QUESTION_FINAL") -> "QUESTION_FINAL"
            utteranceId.startsWith("STATEMENT_FINAL_TOOL") -> "STATEMENT_FINAL_TOOL"
            utteranceId.startsWith("STATEMENT_FINAL") -> "STATEMENT_FINAL"
            else -> return null
        }
        val gen = utteranceId.substringAfterLast('_').toIntOrNull()
        if (gen != null && gen != generation) {
            return null
        }
        return kind
    }
}

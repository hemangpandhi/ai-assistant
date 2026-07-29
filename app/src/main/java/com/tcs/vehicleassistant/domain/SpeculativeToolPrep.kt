package com.tcs.vehicleassistant.domain

import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.utils.DirectCabinCommandRouter
import com.tcs.vehicleassistant.utils.FollowUpRouter

/**
 * Speculative tool prep (plan Tier 1.1): resolve a candidate tool from strong
 * STT partials so the final only confirms/executes — never executes on partial.
 */
object SpeculativeToolPrep {
    @Volatile
    private var candidateTool: String? = null

    @Volatile
    private var candidatePartial: String? = null

    fun onPartial(partial: String) {
        val q = partial.trim()
        if (q.length < 4) {
            clear()
            return
        }
        val tool = DirectCabinCommandRouter.resolve(q)
            ?: FollowUpRouter.resolveDirectTool(q, LLMManager.lastAiResponse)
        if (tool != null) {
            candidateTool = tool
            candidatePartial = q
        }
    }

    /**
     * Returns a tool to execute immediately if the final utterance still maps to
     * the speculative candidate (or freshly resolves the same zero-LLM path).
     * Never executes on partial — call only from the final-commit path.
     */
    fun resolveForFinal(finalQuery: String): String? {
        val fresh = DirectCabinCommandRouter.resolve(finalQuery)
            ?: FollowUpRouter.resolveDirectTool(finalQuery, LLMManager.lastAiResponse)
        val speculative = candidateTool
        val partial = candidatePartial
        clear()
        if (fresh != null) return fresh
        // Final slightly different from partial but same tool family — still allow cache hit
        // only when final contains the partial stem.
        if (speculative != null && partial != null &&
            finalQuery.contains(partial.take(minOf(partial.length, 12)), ignoreCase = true)
        ) {
            return speculative
        }
        return null
    }

    fun clear() {
        candidateTool = null
        candidatePartial = null
    }

    /** True when partial looks like a short cabin command (adaptive endpointing). */
    fun looksLikeCommand(partial: String): Boolean {
        val q = partial.trim().lowercase()
        if (q.length < 5) return false
        return DirectCabinCommandRouter.resolve(q) != null ||
            q.startsWith("turn ") || q.startsWith("set ") ||
            q.startsWith("open ") || q.startsWith("close ") ||
            q.startsWith("play ") || q.startsWith("pause ") ||
            q.contains("temperature") || q.contains("window")
    }
}

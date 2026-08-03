package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConfirmationPolicy

/**
 * Owns the two pending-action slots that [TurnRouter] must not confuse:
 * - ContextGuard / OEM safety confirmation ([pendingConfirmationTool])
 * - Soft conversational offer such as wellness music ([pendingOfferedTool])
 *
 * Phase 1 #4: single place to set/clear/supersede so "yes" never hits the wrong path.
 */
class ConfirmationCoordinator {

    @Volatile
    var pendingConfirmationTool: String? = null
        private set

    @Volatile
    var pendingOfferedTool: String? = null
        private set

    fun setConfirmation(toolCall: String) {
        pendingConfirmationTool = toolCall
        // A hard confirm supersedes any soft offer.
        pendingOfferedTool = null
    }

    fun setSoftOffer(toolCall: String) {
        // Soft offers only when no safety confirm is outstanding.
        if (pendingConfirmationTool == null) {
            pendingOfferedTool = toolCall
        }
    }

    fun clearConfirmation() {
        pendingConfirmationTool = null
    }

    fun clearOffer() {
        pendingOfferedTool = null
    }

    fun clearAll() {
        pendingConfirmationTool = null
        pendingOfferedTool = null
    }

    /**
     * When the user says neither yes nor no, drop stale pending state so the new utterance
     * can route normally. Returns log-friendly labels of what was cleared.
     */
    fun applySupersedeIfNeeded(query: String): List<String> {
        if (query.startsWith("[")) return emptyList()
        val cleared = mutableListOf<String>()
        if (pendingConfirmationTool != null &&
            ConfirmationPolicy.classify(query) == ConfirmationPolicy.Reply.OTHER
        ) {
            pendingConfirmationTool = null
            cleared += "ContextGuardConfirm"
        }
        if (pendingOfferedTool != null &&
            pendingConfirmationTool == null &&
            ConfirmationPolicy.classify(query) == ConfirmationPolicy.Reply.OTHER
        ) {
            pendingOfferedTool = null
            cleared += "SoftOffer"
        }
        return cleared
    }

    /** Snapshot values for [TurnRouter.Input] after [applySupersedeIfNeeded]. */
    fun snapshot(): Snapshot = Snapshot(
        pendingConfirmationTool = pendingConfirmationTool,
        pendingOfferedTool = pendingOfferedTool,
    )

    data class Snapshot(
        val pendingConfirmationTool: String?,
        val pendingOfferedTool: String?,
    )
}

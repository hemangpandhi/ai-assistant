package com.tcs.vehicleassistant.domain

import com.tcs.vehicleassistant.utils.FollowUpRouter

/**
 * Zero-LLM follow-up / direct-command UseCase.
 */
class FollowUpUseCase {
    fun resolve(query: String, lastAssistantMessage: String): String? =
        FollowUpRouter.resolveDirectTool(query, lastAssistantMessage)
}

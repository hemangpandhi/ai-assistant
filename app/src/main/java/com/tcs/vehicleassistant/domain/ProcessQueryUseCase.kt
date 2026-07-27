package com.tcs.vehicleassistant.domain

import com.tcs.vehicleassistant.repository.AgentOrchestrator

/**
 * Domain entry for voice / text queries — keeps reducers free of pipeline logic.
 */
class ProcessQueryUseCase(
    private val orchestrator: AgentOrchestrator,
) {
    operator fun invoke(query: String, retryCount: Int = 0) {
        orchestrator.handleQuery(query, retryCount)
    }
}

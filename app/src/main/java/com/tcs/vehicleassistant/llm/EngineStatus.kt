package com.tcs.vehicleassistant.llm

/**
 * JetPacker-style readiness for the on-device LLM engine.
 * UI / session should prefer [Ready] (or queue) before accepting queries.
 */
sealed class EngineStatus {
    data object Cold : EngineStatus()
    data object Loading : EngineStatus()
    data object Prewarming : EngineStatus()
    data object Ready : EngineStatus()
    data object Unloaded : EngineStatus()

    val isReady: Boolean get() = this is Ready
}

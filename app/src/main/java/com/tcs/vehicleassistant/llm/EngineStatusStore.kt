package com.tcs.vehicleassistant.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive [EngineStatus] owner (UI/UX extension).
 * [com.tcs.vehicleassistant.LLMManager] mutates lifecycle flags and calls [update].
 */
class EngineStatusStore {
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Cold)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun update(
        initializing: Boolean,
        prewarming: Boolean,
        engineLoaded: Boolean,
        conversationLoaded: Boolean,
        modelPath: String,
    ) {
        _status.value = when {
            initializing -> EngineStatus.Loading
            prewarming -> EngineStatus.Prewarming
            engineLoaded && conversationLoaded -> EngineStatus.Ready
            !engineLoaded && modelPath.isEmpty() -> EngineStatus.Cold
            else -> EngineStatus.Unloaded
        }
    }
}

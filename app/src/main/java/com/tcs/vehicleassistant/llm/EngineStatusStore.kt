package com.tcs.vehicleassistant.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive [EngineStatus] owner for the UI/UX engine adapter.
 */
class EngineStatusStore {
    private val _status = MutableStateFlow<EngineStatus>(
        com.assistant.api.llm.EngineStatus.Cold,
    )
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun set(status: EngineStatus) {
        _status.value = status
    }

    fun update(
        initializing: Boolean,
        prewarming: Boolean,
        engineLoaded: Boolean,
        conversationLoaded: Boolean,
        modelPath: String,
    ) {
        _status.value = when {
            initializing -> com.assistant.api.llm.EngineStatus.Loading
            prewarming -> com.assistant.api.llm.EngineStatus.Prewarming
            engineLoaded && conversationLoaded -> com.assistant.api.llm.EngineStatus.Ready
            !engineLoaded && modelPath.isEmpty() -> com.assistant.api.llm.EngineStatus.Cold
            else -> com.assistant.api.llm.EngineStatus.Unloaded
        }
    }
}

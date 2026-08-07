package com.assistant.api.llm

import kotlinx.coroutines.flow.StateFlow

/**
 * Coarse engine readiness for UI / TTFR gating (no LiteRT types).
 */
sealed class EngineStatus {
    data object Cold : EngineStatus()
    data object Loading : EngineStatus()
    data object Prewarming : EngineStatus()
    data object Ready : EngineStatus()
    data object Unloaded : EngineStatus()

    val isReady: Boolean get() = this is Ready
}

/**
 * Readiness + conversation metadata port. Adapters wrap host LLM singletons.
 */
interface LlmSessionPort {
    val status: StateFlow<EngineStatus>

    fun isReady(): Boolean
    fun isInitializing(): Boolean
    fun isPrewarming(): Boolean

    var lastAiResponse: String
    var lastVehicleState: String
    var lastInjectedTools: String
    var isFirstMessage: Boolean

    val currentModelPath: String
    val activeBackendString: String

    suspend fun ensureReady(context: android.content.Context, force: Boolean = false)
    suspend fun getSystemPrompt(context: android.content.Context, query: String = ""): String
    suspend fun unload()
}

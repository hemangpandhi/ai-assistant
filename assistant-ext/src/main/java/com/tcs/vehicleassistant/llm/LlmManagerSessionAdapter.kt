package com.tcs.vehicleassistant.llm

import android.content.Context
import com.assistant.api.llm.EngineStatus
import com.assistant.api.llm.LlmSessionPort
import com.tcs.vehicleassistant.LLMManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapts refactor [LLMManager] to [LlmSessionPort] without modifying the singleton.
 */
class LlmManagerSessionAdapter(
    private val engine: LlmEngine = LiteRtLlmEngine(),
) : LlmSessionPort {

    private val statusStore = EngineStatusStore()

    override val status: StateFlow<EngineStatus>
        get() {
            refresh()
            return statusStore.status
        }

    override fun isReady(): Boolean = LLMManager.isReady()

    override fun isInitializing(): Boolean = LLMManager.isInitializing

    override fun isPrewarming(): Boolean = LLMManager.isPrewarming

    override var lastAiResponse: String
        get() = LLMManager.lastAiResponse
        set(value) {
            LLMManager.lastAiResponse = value
        }

    override var lastVehicleState: String
        get() = LLMManager.lastVehicleState
        set(value) {
            LLMManager.lastVehicleState = value
        }

    override var lastInjectedTools: String
        get() = LLMManager.lastInjectedTools
        set(value) {
            LLMManager.lastInjectedTools = value
        }

    override var isFirstMessage: Boolean
        get() = LLMManager.isFirstMessage
        set(value) {
            LLMManager.isFirstMessage = value
        }

    override val currentModelPath: String
        get() = LLMManager.currentModelPath

    override val activeBackendString: String
        get() = LLMManager.activeBackendString

    override suspend fun ensureReady(context: Context, force: Boolean) {
        engine.ensureReady(context, force)
        refresh()
    }

    override suspend fun getSystemPrompt(context: Context, query: String): String =
        LLMManager.getSystemPrompt(context, query)

    override suspend fun unload() {
        engine.unload()
        refresh()
    }

    private fun refresh() {
        statusStore.update(
            initializing = LLMManager.isInitializing,
            prewarming = LLMManager.isPrewarming,
            engineLoaded = LLMManager.engine != null,
            conversationLoaded = LLMManager.conversation != null,
            modelPath = LLMManager.currentModelPath,
        )
    }
}

package com.tcs.vehicleassistant.repository

import com.tcs.vehicleassistant.assistant.agent.AgentState
import com.tcs.vehicleassistant.assistant.agent.AgentEffect

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.utils.ToolCallParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Runs [AgentOrchestrator] inside LocalLLMActivity with optional TTS and chat UI callbacks.
 */
class InAppOrchestratorBridge(
    context: Context,
    private val audioManager: IAudioManager,
    private val scope: CoroutineScope
) {
    var enableTts: Boolean = false

    private val orchestrator = AgentOrchestrator(
        context.applicationContext,
        audioManager,
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolRegistry>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.IToolExecutor>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ConversationMemory>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.core.ContextGuard>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.core.DirectToolResolver>()
    )

    var onStreaming: ((String) -> Unit)? = null
    var onSpeaking: ((String) -> Unit)? = null
    var onThinking: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    var onLaunchIntent: ((Intent) -> Unit)? = null

    init {
        orchestrator.state.onEach { state ->
            when (state) {
                is AgentState.Thinking -> onThinking?.invoke()
                is AgentState.Streaming -> onStreaming?.invoke(ToolCallParser.stripToolTags(state.displayMsg))
                is AgentState.Speaking -> onSpeaking?.invoke(state.finalMsg)
                is AgentState.Error -> onError?.invoke(state.message)
                is AgentState.Idle -> onIdle?.invoke()
            }
        }.launchIn(scope)

        orchestrator.events.onEach { event ->
            when (event) {
                is AgentEffect.LaunchIntent -> onLaunchIntent?.invoke(event.intent)
                else -> {}
            }
        }.launchIn(scope)
    }

    fun isProcessing(): Boolean = orchestrator.isProcessing()

    fun handleQuery(query: String) {
        orchestrator.handleQuery(query)
    }

    fun destroy() {
        orchestrator.destroy()
    }
}

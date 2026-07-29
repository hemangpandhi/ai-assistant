package com.tcs.vehicleassistant.repository

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags
import com.tcs.vehicleassistant.data.memory.MemoryManagerStore
import com.tcs.vehicleassistant.domain.ExecuteToolUseCase
import com.tcs.vehicleassistant.domain.QueryPipeline
import com.tcs.vehicleassistant.domain.SpeechPresenter
import com.tcs.vehicleassistant.domain.ToolLoop
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.repository.uiux.OrchestratorEvent
import com.tcs.vehicleassistant.repository.uiux.OrchestratorState
import com.tcs.vehicleassistant.utils.ToolCallParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Runs [UiUxAgentOrchestrator] inside LocalLLMActivity with optional TTS and chat UI callbacks.
 * Prefers the Koin-shared orchestrator when available; otherwise builds a bridge-scoped instance
 * (in-app TTS uses a different [IAudioManager]).
 */
class InAppOrchestratorBridge(
    context: Context,
    private val audioManager: IAudioManager,
    private val scope: CoroutineScope
) {
    var enableTts: Boolean = false

    private val orchestrator = AgentOrchestrator(context.applicationContext, audioManager)

    var onStreaming: ((String) -> Unit)? = null
    var onSpeaking: ((String) -> Unit)? = null
    var onThinking: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    var onLaunchIntent: ((Intent) -> Unit)? = null

    init {
        orchestrator.state.onEach { state ->
            when (state) {
                is OrchestratorState.Thinking -> onThinking?.invoke()
                is OrchestratorState.Streaming -> onStreaming?.invoke(ToolCallParser.stripToolTags(state.displayMsg))
                is OrchestratorState.Speaking -> onSpeaking?.invoke(state.finalMsg)
                is OrchestratorState.Error -> onError?.invoke(state.message)
                is OrchestratorState.Idle -> onIdle?.invoke()
            }
        }.launchIn(scope)

        orchestrator.events.onEach { event ->
            when (event) {
                is OrchestratorEvent.LaunchIntent -> onLaunchIntent?.invoke(event.intent)
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

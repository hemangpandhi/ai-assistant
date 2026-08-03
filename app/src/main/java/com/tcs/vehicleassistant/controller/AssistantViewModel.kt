package com.tcs.vehicleassistant.controller

import android.content.Context
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import com.tcs.vehicleassistant.repository.OrchestratorEvent
import com.tcs.vehicleassistant.repository.OrchestratorState
import com.tcs.vehicleassistant.hardware.IAudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.java.KoinJavaComponent.getKoin

/**
 * ViewModel that owns the UI state for the Assistant.
 *
 * Responsibilities:
 *  - Receive UI intents (Speech Recognition, Text input)
 *  - Delegate queries to AgentOrchestrator
 *  - Map OrchestratorState to AssistantUiState
 *
 * The View (AssistantSession) observes [uiState] and [events] and renders accordingly.
 */
class AssistantViewModel(
    private val context: Context,
    private val audioManager: IAudioManager
) {
    private val orchestrator = AgentOrchestrator(
        context,
        audioManager,
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolRegistry>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.IToolExecutor>()
    )

    // ── Public observable state ──────────────────────────────────────────────
    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ViewModelEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ViewModelEvent> = _events.asSharedFlow()

    // ── Internal state ──────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        audioManager.setRecognitionListener(
            onReadyForSpeech = {
                _uiState.value = AssistantUiState.Listening()
            },
            onBeginningOfSpeech = { },
            onEndOfSpeech = {
                _uiState.value = AssistantUiState.Thinking()
            },
            onResult = { spokenText ->
                audioManager.stopSpeaking()
                _events.tryEmit(ViewModelEvent.SetInputText(spokenText))
                processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.ProcessQuery(spokenText))
            },
            onEmptyResult = {
                _uiState.value = AssistantUiState.Error("I didn't hear anything.")
                // Do not dismiss automatically so user can try again
            },
            onError = { errorCode ->
                val errorMsg = mapSpeechError(errorCode)
                _uiState.value = AssistantUiState.Error(errorMsg)
                // Do not dismiss automatically so user can try again
            },
            onPartial = { partialText ->
                _uiState.value = AssistantUiState.Listening(partialText)
                _events.tryEmit(ViewModelEvent.SetInputText(partialText))
            }
        )

        // Map Orchestrator State to UI State
        scope.launch {
            orchestrator.state.collect { state ->
                _uiState.value = when (state) {
                    is OrchestratorState.Idle -> AssistantUiState.Idle
                    is OrchestratorState.Thinking -> AssistantUiState.Thinking(state.query)
                    is OrchestratorState.Streaming -> AssistantUiState.Streaming(state.displayMsg)
                    is OrchestratorState.Speaking -> AssistantUiState.Speaking(state.finalMsg)
                    is OrchestratorState.Error -> AssistantUiState.Error(state.message)
                }
            }
        }

        // Map Orchestrator Events to UI Events
        scope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.ShowToast -> _events.tryEmit(ViewModelEvent.ShowToast(event.message))
                    is OrchestratorEvent.SetInputEnabled -> _events.tryEmit(ViewModelEvent.SetInputEnabled(event.enabled))
                    is OrchestratorEvent.LaunchIntent -> _events.tryEmit(ViewModelEvent.LaunchIntent(event.intent))
                    is OrchestratorEvent.StartListening -> _events.tryEmit(ViewModelEvent.StartListening)
                    is OrchestratorEvent.FinishSession -> _events.tryEmit(ViewModelEvent.FinishSession)
                }
            }
        }
    }

    // ── Public API (MVI) ────────────────────────────────────────────────────

    private var isDirectSpeaking = false

    fun isProcessing(): Boolean = orchestrator.isProcessing() || isDirectSpeaking

    val lastTtsUpdateTime: Long
        get() = orchestrator.lastTtsUpdateTime

    val ttsSpokenLength: Int
        get() = orchestrator.ttsSpokenLength

    fun processIntent(intent: AssistantIntent) {
        when (intent) {
            is AssistantIntent.ProcessQuery -> {
                orchestrator.handleQuery(intent.query)
            }
            is AssistantIntent.ProcessProactiveEvent -> {
                orchestrator.triggerProactiveEvent(intent.prompt)
            }
            is AssistantIntent.StartListening -> {
                audioManager.startListening()
            }
            is AssistantIntent.StopListening -> {
                audioManager.stopListening()
            }
            is AssistantIntent.Cancel -> {
                orchestrator.resetState()
                _events.tryEmit(ViewModelEvent.FinishSession)
            }
            is AssistantIntent.ConfirmTool -> {
                orchestrator.handleConfirmation(intent.accepted)
            }
        }
    }

    fun destroy() {
        scope.cancel()
        orchestrator.destroy()
    }

    private fun mapSpeechError(errorCode: Int): String {
        return when (errorCode) {
            android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error"
            android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "No recognition result matched"
            android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            android.speech.SpeechRecognizer.ERROR_SERVER -> "Error from server"
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown recognition error"
        }
    }

    fun speakAndDismiss(text: String) {
        scope.launch {
            isDirectSpeaking = true
            _uiState.value = AssistantUiState.Speaking(text)
            audioManager.speak(text, "direct_speech")
            delay(5000) // Give it time to speak, or rely on a TTS callback if available. For now delay is safe.
            isDirectSpeaking = false
            _events.tryEmit(ViewModelEvent.FinishSession)
        }
    }
}

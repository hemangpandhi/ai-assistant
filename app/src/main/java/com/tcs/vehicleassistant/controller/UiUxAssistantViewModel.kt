package com.tcs.vehicleassistant.controller

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.api.llm.LlmSessionPort
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.tcs.vehicleassistant.domain.ProcessQueryUseCase
import com.tcs.vehicleassistant.domain.SpeculativeToolPrep
import com.tcs.vehicleassistant.hardware.EndpointingProfileSelector
import com.tcs.vehicleassistant.hardware.SessionAudioPort
import com.tcs.vehicleassistant.hardware.SpeechRecognitionErrors
import com.tcs.vehicleassistant.repository.UiUxAgentOrchestrator
import com.tcs.vehicleassistant.repository.uiux.OrchestratorEvent
import com.tcs.vehicleassistant.repository.uiux.OrchestratorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * androidx [ViewModel] hosting presentation MVI intents over the shared agent pipeline.
 */
class UiUxAssistantViewModel(
    private val context: Context,
    private val audioManager: SessionAudioPort,
    private val orchestrator: UiUxAgentOrchestrator,
    private val processQuery: ProcessQueryUseCase,
    private val llmSession: LlmSessionPort,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Live STT partial/final text — StateFlow so UI is not dependent on SharedFlow subscribers. */
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _events = MutableSharedFlow<UiUxViewModelEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<UiUxViewModelEvent> = _events.asSharedFlow()

    init {
        audioManager.setRecognitionListener(
            onReadyForSpeech = {
                _liveTranscript.value = ""
                _uiState.value = AssistantUiState.Listening()
            },
            onBeginningOfSpeech = {
                // Barge-in lite: supersede in-flight Think/Act/Speak when user talks again.
                if (orchestrator.isProcessing()) {
                    orchestrator.cancelInFlight()
                }
                runCatching { audioManager.stopSpeaking() }
                _uiState.value = AssistantUiState.Listening()
            },
            onEndOfSpeech = {
                // Partial-driven UI only: stay Listening until final commits Phase B.
                // Never jump to Thinking on endpoint alone.
            },
            onResult = { spokenText ->
                // Phase A: utterance text committed (transcript + input).
                audioManager.stopSpeaking()
                _liveTranscript.value = spokenText
                _events.tryEmit(UiUxViewModelEvent.SetInputText(spokenText))
                // Phase B: FollowUp / LLM on agent dispatcher — does not block re-listen.
                dispatch(AssistantUiIntent.SubmitQuery(spokenText))
            },
            onEmptyResult = {
                SpeculativeToolPrep.clear()
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
                _liveTranscript.value = ""
                // Soft miss — keep session open and re-arm ear.
                _uiState.value = AssistantUiState.Listening()
                if (!audioManager.isActivelyListening()) {
                    _events.tryEmit(UiUxViewModelEvent.StartListening)
                }
            },
            onError = { errorCode ->
                SpeculativeToolPrep.clear()
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
                val softMiss = SpeechRecognitionErrors.isSoftMiss(errorCode)
                val recoverable = SpeechRecognitionErrors.isRecoverable(errorCode)
                when {
                    softMiss -> {
                        _liveTranscript.value = ""
                        _uiState.value = AssistantUiState.Listening()
                        if (!audioManager.isActivelyListening()) {
                            _events.tryEmit(UiUxViewModelEvent.StartListening)
                        }
                    }
                    recoverable -> {
                        // Do not flash "Client side error (5)" — cancel/stop often emit this,
                        // and unexpected ones self-heal via silent re-arm / recreate.
                        // Never re-arm while Speaking/Streaming — that ERROR_CLIENT is intentional.
                        AssistantDebugLog.w(
                            "VM",
                            "STT recoverable $errorCode — silent re-arm",
                        )
                        _liveTranscript.value = ""
                        val busy =
                            _uiState.value is AssistantUiState.Speaking ||
                                _uiState.value is AssistantUiState.Streaming ||
                                _uiState.value is AssistantUiState.Thinking
                        if (!busy) {
                            _uiState.value = AssistantUiState.Listening()
                            if (!audioManager.isActivelyListening()) {
                                _events.tryEmit(UiUxViewModelEvent.StartListening)
                            }
                        }
                    }
                    else -> {
                        val errorMsg = SpeechRecognitionErrors.userMessage(errorCode)
                        _uiState.value = AssistantUiState.Error(errorMsg)
                        viewModelScope.launch {
                            delay(2000)
                            _events.tryEmit(UiUxViewModelEvent.FinishSession)
                        }
                    }
                }
            },
            onPartial = { partialText ->
                if (partialText.isNotBlank()) {
                    SpeculativeToolPrep.onPartial(partialText, llmSession.lastAiResponse)
                    audioManager.setEndpointingProfile(EndpointingProfileSelector.forPartial(partialText))
                    _liveTranscript.value = partialText
                    _events.tryEmit(UiUxViewModelEvent.SetInputText(partialText))
                }
            }
        )

        viewModelScope.launch {
            orchestrator.state.collect { state ->
                _uiState.value = when (state) {
                    is OrchestratorState.Idle -> {
                        // Don't clobber an already-open ear (pre-armed STT / Listening).
                        if (audioManager.isActivelyListening()) {
                            AssistantUiState.Listening()
                        } else {
                            AssistantUiState.Idle
                        }
                    }
                    is OrchestratorState.Thinking -> AssistantUiState.Thinking()
                    is OrchestratorState.Streaming -> AssistantUiState.Streaming(state.displayMsg)
                    is OrchestratorState.Speaking -> AssistantUiState.Speaking(state.finalMsg)
                    is OrchestratorState.Error -> AssistantUiState.Error(state.message)
                }
            }
        }

        viewModelScope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.ShowToast -> _events.tryEmit(UiUxViewModelEvent.ShowToast(event.message))
                    is OrchestratorEvent.SetInputEnabled -> _events.tryEmit(UiUxViewModelEvent.SetInputEnabled(event.enabled))
                    is OrchestratorEvent.LaunchIntent -> _events.tryEmit(UiUxViewModelEvent.LaunchIntent(event.intent))
                    is OrchestratorEvent.StartListening -> _events.tryEmit(UiUxViewModelEvent.StartListening)
                    is OrchestratorEvent.FinishSession -> _events.tryEmit(UiUxViewModelEvent.FinishSession)
                    is OrchestratorEvent.AffectiveMood ->
                        _events.tryEmit(UiUxViewModelEvent.AffectiveMood(event.mood))
                }
            }
        }
    }

    fun dispatch(intent: AssistantUiIntent) {
        when (intent) {
            is AssistantUiIntent.SubmitQuery -> processQuery(intent.query, intent.retryCount)
            AssistantUiIntent.Reset -> {
                SpeculativeToolPrep.clear()
                orchestrator.resetState()
            }
        }
    }

    fun isProcessing(): Boolean = orchestrator.isProcessing()

    fun cancelInFlight() = orchestrator.cancelInFlight()

    val lastTtsUpdateTime: Long
        get() = orchestrator.lastTtsUpdateTime

    val ttsSpokenLength: Int
        get() = orchestrator.ttsSpokenLength

    fun handleQuery(query: String, retryCount: Int = 0) {
        dispatch(AssistantUiIntent.SubmitQuery(query, retryCount))
    }

    fun resetUiState() {
        _liveTranscript.value = ""
        SpeculativeToolPrep.clear()
        dispatch(AssistantUiIntent.Reset)
    }

    fun destroy() {
        // viewModelScope cancelled by [onCleared]; shared orchestrator lives with the service.
    }

    override fun onCleared() {
        super.onCleared()
    }
}

sealed interface AssistantUiIntent {
    data class SubmitQuery(val query: String, val retryCount: Int = 0) : AssistantUiIntent
    data object Reset : AssistantUiIntent
}

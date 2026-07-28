package com.tcs.vehicleassistant.controller

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.tcs.vehicleassistant.domain.ProcessQueryUseCase
import com.tcs.vehicleassistant.domain.SpeculativeToolPrep
import com.tcs.vehicleassistant.hardware.EndpointingProfile
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import com.tcs.vehicleassistant.repository.OrchestratorEvent
import com.tcs.vehicleassistant.repository.OrchestratorState
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
class AssistantViewModel(
    private val context: Context,
    private val audioManager: IAudioManager,
    private val orchestrator: AgentOrchestrator,
) : ViewModel() {
    private val processQuery = ProcessQueryUseCase(orchestrator)

    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Live STT partial/final text — StateFlow so UI is not dependent on SharedFlow subscribers. */
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _events = MutableSharedFlow<ViewModelEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ViewModelEvent> = _events.asSharedFlow()

    init {
        audioManager.setRecognitionListener(
            onReadyForSpeech = {
                _uiState.value = AssistantUiState.Listening()
            },
            onBeginningOfSpeech = {
                // Barge-in lite: supersede in-flight Think/Act/Speak when user talks again.
                if (orchestrator.isProcessing()) {
                    orchestrator.cancelInFlight()
                }
                runCatching { audioManager.stopSpeaking() }
                _uiState.value = AssistantUiState.Listening
            },
            onEndOfSpeech = {
                _uiState.value = AssistantUiState.Thinking()
            },
            onResult = { spokenText ->
                // Phase A: utterance text committed (transcript + input).
                audioManager.stopSpeaking()
                _liveTranscript.value = spokenText
                _events.tryEmit(ViewModelEvent.SetInputText(spokenText))
                // Phase B: FollowUp / LLM on agent dispatcher — does not block re-listen.
                dispatch(AssistantUiIntent.SubmitQuery(spokenText))
            },
            onEmptyResult = {
                SpeculativeToolPrep.clear()
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
                _liveTranscript.value = ""
                // Soft miss — keep session open and re-arm ear.
                _uiState.value = AssistantUiState.Listening
                if (!audioManager.isActivelyListening()) {
                    _events.tryEmit(ViewModelEvent.StartListening)
                }
            },
            onError = { errorCode ->
                SpeculativeToolPrep.clear()
                val errorMsg = mapSpeechError(errorCode)
                _uiState.value = AssistantUiState.Error(errorMsg)
                // Do not dismiss automatically so user can try again
            },
            onPartial = { partialText ->
                _uiState.value = AssistantUiState.Listening(partialText)
                _events.tryEmit(ViewModelEvent.SetInputText(partialText))
            }
        )

        viewModelScope.launch {
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

        viewModelScope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.ShowToast -> _events.tryEmit(ViewModelEvent.ShowToast(event.message))
                    is OrchestratorEvent.SetInputEnabled -> _events.tryEmit(ViewModelEvent.SetInputEnabled(event.enabled))
                    is OrchestratorEvent.LaunchIntent -> _events.tryEmit(ViewModelEvent.LaunchIntent(event.intent))
                    is OrchestratorEvent.StartListening -> _events.tryEmit(ViewModelEvent.StartListening)
                    is OrchestratorEvent.FinishSession -> _events.tryEmit(ViewModelEvent.FinishSession)
                    is OrchestratorEvent.AffectiveMood ->
                        _events.tryEmit(ViewModelEvent.AffectiveMood(event.mood))
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

    private fun endpointingForPartial(partial: String): EndpointingProfile {
        val q = partial.trim().lowercase()
        if (SpeculativeToolPrep.looksLikeCommand(partial)) {
            return EndpointingProfile.ShortCommand
        }
        if (q.startsWith("what ") || q.startsWith("why ") || q.startsWith("how ") ||
            q.startsWith("where ") || q.startsWith("when ") || q.startsWith("who ") ||
            q.contains("tell me") || q.contains("explain")
        ) {
            return EndpointingProfile.OpenQuestion
        }
        return EndpointingProfile.Default
    }

    private fun mapSpeechError(errorCode: Int): String {
        val label = com.tcs.vehicleassistant.hardware.AndroidAudioManager.sttErrorLabel(errorCode)
        return when (errorCode) {
            android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client side error ($errorCode/$label)"
            android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "No recognition result matched ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_SERVER -> "Error from server ($errorCode)"
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input ($errorCode)"
            else -> "Unknown recognition error ($errorCode/$label)"
        }
    }
}

sealed interface AssistantUiIntent {
    data class SubmitQuery(val query: String, val retryCount: Int = 0) : AssistantUiIntent
    data object Reset : AssistantUiIntent
}

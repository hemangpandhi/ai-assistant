package com.tcs.vehicleassistant.controller

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tcs.vehicleassistant.domain.ProcessQueryUseCase
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

    private val _events = MutableSharedFlow<ViewModelEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ViewModelEvent> = _events.asSharedFlow()

    init {
        audioManager.setRecognitionListener(
            onReadyForSpeech = {
                _uiState.value = AssistantUiState.Listening
            },
            onBeginningOfSpeech = { },
            onEndOfSpeech = {
                _uiState.value = AssistantUiState.Thinking
            },
            onResult = { spokenText ->
                audioManager.stopSpeaking()
                _events.tryEmit(ViewModelEvent.SetInputText(spokenText))
                dispatch(AssistantUiIntent.SubmitQuery(spokenText))
            },
            onEmptyResult = {
                _uiState.value = AssistantUiState.Error("I didn't hear anything.")
            },
            onError = { errorCode ->
                val errorMsg = mapSpeechError(errorCode)
                _uiState.value = AssistantUiState.Error(errorMsg)
                val recoverable = errorCode == android.speech.SpeechRecognizer.ERROR_CLIENT ||
                    errorCode == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                if (!recoverable) {
                    viewModelScope.launch {
                        delay(2000)
                        _events.tryEmit(ViewModelEvent.FinishSession)
                    }
                }
            },
            onPartial = { partialText ->
                _events.tryEmit(ViewModelEvent.SetInputText(partialText))
            }
        )

        viewModelScope.launch {
            orchestrator.state.collect { state ->
                _uiState.value = when (state) {
                    is OrchestratorState.Idle -> {
                        // Don't clobber an already-open ear (pre-armed STT / Listening).
                        if (audioManager.isActivelyListening()) {
                            AssistantUiState.Listening
                        } else {
                            AssistantUiState.Idle
                        }
                    }
                    is OrchestratorState.Thinking -> AssistantUiState.Thinking
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
                }
            }
        }
    }

    fun dispatch(intent: AssistantUiIntent) {
        when (intent) {
            is AssistantUiIntent.SubmitQuery -> processQuery(intent.query, intent.retryCount)
            AssistantUiIntent.Reset -> orchestrator.resetState()
        }
    }

    fun isProcessing(): Boolean = orchestrator.isProcessing()

    val lastTtsUpdateTime: Long
        get() = orchestrator.lastTtsUpdateTime

    val ttsSpokenLength: Int
        get() = orchestrator.ttsSpokenLength

    fun handleQuery(query: String, retryCount: Int = 0) {
        dispatch(AssistantUiIntent.SubmitQuery(query, retryCount))
    }

    fun resetUiState() {
        dispatch(AssistantUiIntent.Reset)
    }

    fun destroy() {
        // viewModelScope cancelled by [onCleared]; shared orchestrator lives with the service.
    }

    override fun onCleared() {
        super.onCleared()
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

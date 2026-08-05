package com.tcs.vehicleassistant.controller

import android.content.Context
import android.util.Log
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import com.tcs.vehicleassistant.repository.OrchestratorEvent
import com.tcs.vehicleassistant.repository.OrchestratorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the assistant overlay.
 *
 * SRP: maps ear / UI intents ↔ observable UI state. Query execution is delegated to
 * [AgentOrchestrator] unless [AssistantConfig.isEarTestMode] is on (mic→STT bring-up).
 */
class AssistantViewModel(
    private val context: Context,
    private val audioManager: IAudioManager,
) {
    private val orchestrator = AgentOrchestrator(context, audioManager)

    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ViewModelEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ViewModelEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isDirectSpeaking = false

    init {
        audioManager.setRecognitionListener(
            onReadyForSpeech = {
                _uiState.value = AssistantUiState.Listening()
            },
            onBeginningOfSpeech = { },
            onEndOfSpeech = {
                if (!AssistantConfig.isEarTestMode(context)) {
                    _uiState.value = AssistantUiState.Thinking()
                }
            },
            onResult = { spokenText ->
                audioManager.stopSpeaking()
                _events.tryEmit(ViewModelEvent.SetInputText(spokenText))
                if (AssistantConfig.isEarTestMode(context)) {
                    // Ear bring-up: surface transcript only — do not orchestrate / LLM / tools.
                    Log.i(TAG, "EAR_TEST_MODE final='$spokenText' (orchestration skipped)")
                    _uiState.value = AssistantUiState.Listening(spokenText)
                    scope.launch {
                        delay(EAR_TEST_REARM_MS)
                        audioManager.startListeningForced()
                    }
                } else {
                    handleQuery(spokenText)
                }
            },
            onEmptyResult = {
                _uiState.value = AssistantUiState.Error("I didn't hear anything.")
                if (AssistantConfig.isEarTestMode(context)) {
                    scope.launch {
                        delay(EAR_TEST_REARM_MS)
                        audioManager.startListeningForced()
                    }
                }
            },
            onError = { errorCode ->
                val errorMsg = mapSpeechError(errorCode)
                _uiState.value = AssistantUiState.Error(errorMsg)
                if (AssistantConfig.isEarTestMode(context)) {
                    scope.launch {
                        delay(EAR_TEST_REARM_MS)
                        audioManager.startListeningForced()
                    }
                }
            },
            onPartial = { partialText ->
                _uiState.value = AssistantUiState.Listening(partialText)
                _events.tryEmit(ViewModelEvent.SetInputText(partialText))
            },
        )

        scope.launch {
            orchestrator.state.collect { state ->
                if (AssistantConfig.isEarTestMode(context)) return@collect
                _uiState.value = when (state) {
                    is OrchestratorState.Idle -> AssistantUiState.Idle
                    is OrchestratorState.Thinking -> AssistantUiState.Thinking(state.query)
                    is OrchestratorState.Streaming -> AssistantUiState.Streaming(state.displayMsg)
                    is OrchestratorState.Speaking -> AssistantUiState.Speaking(state.finalMsg)
                    is OrchestratorState.Error -> AssistantUiState.Error(state.message)
                }
            }
        }

        scope.launch {
            orchestrator.events.collect { event ->
                if (AssistantConfig.isEarTestMode(context)) return@collect
                when (event) {
                    is OrchestratorEvent.ShowToast ->
                        _events.tryEmit(ViewModelEvent.ShowToast(event.message))
                    is OrchestratorEvent.SetInputEnabled ->
                        _events.tryEmit(ViewModelEvent.SetInputEnabled(event.enabled))
                    is OrchestratorEvent.LaunchIntent ->
                        _events.tryEmit(ViewModelEvent.LaunchIntent(event.intent))
                    is OrchestratorEvent.StartListening ->
                        _events.tryEmit(ViewModelEvent.StartListening)
                    is OrchestratorEvent.FinishSession ->
                        _events.tryEmit(ViewModelEvent.FinishSession)
                }
            }
        }
    }

    fun isProcessing(): Boolean {
        if (AssistantConfig.isEarTestMode(context)) return false
        return orchestrator.isProcessing() || isDirectSpeaking
    }

    val lastTtsUpdateTime: Long
        get() = orchestrator.lastTtsUpdateTime

    val ttsSpokenLength: Int
        get() = orchestrator.ttsSpokenLength

    fun handleQuery(query: String, retryCount: Int = 0) {
        if (AssistantConfig.isEarTestMode(context)) {
            Log.i(TAG, "EAR_TEST_MODE drop handleQuery='$query'")
            _uiState.value = AssistantUiState.Listening(query)
            _events.tryEmit(ViewModelEvent.SetInputText(query))
            return
        }
        orchestrator.handleQuery(query, retryCount)
    }

    fun resetUiState() {
        orchestrator.resetState()
    }

    fun resetState() {
        orchestrator.resetState()
    }

    fun destroy() {
        scope.cancel()
        orchestrator.destroy()
    }

    private fun mapSpeechError(errorCode: Int): String =
        when (errorCode) {
            android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Insufficient permissions"
            android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error"
            android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "No recognition result matched"
            android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            android.speech.SpeechRecognizer.ERROR_SERVER -> "Error from server"
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown recognition error"
        }

    fun speakAndDismiss(text: String) {
        scope.launch {
            isDirectSpeaking = true
            _uiState.value = AssistantUiState.Speaking(text)
            audioManager.speak(text, "direct_speech")
            delay(5000)
            isDirectSpeaking = false
            _events.tryEmit(ViewModelEvent.FinishSession)
        }
    }

    companion object {
        private const val TAG = "AssistantViewModel"
        /** Pause before re-arming the ear in test mode so the final caption is readable. */
        private const val EAR_TEST_REARM_MS = 800L
    }
}

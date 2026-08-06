package com.tcs.vehicleassistant.controller

import android.content.Context
import android.util.Log
import com.tcs.vehicleassistant.assistant.agent.AgentEffect
import com.tcs.vehicleassistant.assistant.agent.AgentState
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.repository.AgentOrchestrator
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
    private val orchestrator = AgentOrchestrator(
        context,
        audioManager,
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolRegistry>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.IToolExecutor>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ConversationMemory>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.core.ContextGuard>(),
        org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.core.DirectToolResolver>(),
    )

    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ViewModelEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ViewModelEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isDirectSpeaking = false

    /** Caps EAR_TEST_MODE error re-arms so a failing Google STT path cannot thrash the mic. */
    private var earTestErrorRearms = 0

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
                    Log.i(TAG, "EAR_TEST_MODE final='$spokenText' (orchestration skipped)")
                    _uiState.value = AssistantUiState.Listening(spokenText)
                    earTestErrorRearms = 0
                    scope.launch {
                        delay(EAR_TEST_REARM_MS)
                        audioManager.startListeningForced()
                    }
                } else {
                    processIntent(AssistantIntent.ProcessQuery(spokenText))
                }
            },
            onEmptyResult = {
                _uiState.value = AssistantUiState.Error("I didn't hear anything.")
                maybeRearmEarAfterTransientFailure("empty")
            },
            onError = { errorCode ->
                val errorMsg = mapSpeechError(errorCode)
                _uiState.value = AssistantUiState.Error(errorMsg)
                maybeRearmEarAfterTransientFailure("error=$errorCode")
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
                    is AgentState.Idle -> AssistantUiState.Idle
                    is AgentState.Thinking -> AssistantUiState.Thinking(state.query)
                    is AgentState.Streaming -> AssistantUiState.Streaming(state.displayMsg)
                    is AgentState.Speaking -> AssistantUiState.Speaking(state.finalMsg)
                    is AgentState.Error -> AssistantUiState.Error(state.message)
                }
            }
        }

        scope.launch {
            orchestrator.events.collect { event ->
                if (AssistantConfig.isEarTestMode(context)) return@collect
                when (event) {
                    is AgentEffect.ShowToast ->
                        _events.tryEmit(ViewModelEvent.ShowToast(event.message))
                    is AgentEffect.SetInputEnabled ->
                        _events.tryEmit(ViewModelEvent.SetInputEnabled(event.enabled))
                    is AgentEffect.LaunchIntent ->
                        _events.tryEmit(ViewModelEvent.LaunchIntent(event.intent))
                    is AgentEffect.StartListening ->
                        _events.tryEmit(ViewModelEvent.StartListening)
                    is AgentEffect.FinishSession ->
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

    fun processIntent(intent: AssistantIntent) {
        when (intent) {
            is AssistantIntent.ProcessQuery -> {
                if (AssistantConfig.isEarTestMode(context)) {
                    Log.i(TAG, "EAR_TEST_MODE drop ProcessQuery='${intent.query}'")
                    _uiState.value = AssistantUiState.Listening(intent.query)
                    _events.tryEmit(ViewModelEvent.SetInputText(intent.query))
                    return
                }
                orchestrator.handleQuery(intent.query)
            }
            is AssistantIntent.ProcessProactiveEvent -> {
                if (AssistantConfig.isEarTestMode(context)) return
                orchestrator.triggerProactiveEvent(intent.prompt)
            }
            is AssistantIntent.StartListening -> {
                audioManager.startListeningForced()
            }
            is AssistantIntent.StopListening -> {
                audioManager.stopListening()
            }
            is AssistantIntent.InterruptSpeech -> {
                orchestrator.interruptSpeech()
            }
            is AssistantIntent.ResetTurn -> {
                // New session / re-listen: drop prior agent work without dismissing UI
                // and without emitting FinishSession (that killed mic + overlay).
                orchestrator.resetState()
            }
            is AssistantIntent.Cancel -> {
                orchestrator.resetState()
                _events.tryEmit(ViewModelEvent.FinishSession)
            }
            is AssistantIntent.ClearState -> {
                orchestrator.resetState()
                _uiState.value = AssistantUiState.Idle
            }
            is AssistantIntent.ConfirmTool -> {
                if (AssistantConfig.isEarTestMode(context)) return
                orchestrator.handleConfirmation(intent.accepted)
            }
        }
    }

    fun destroy() {
        scope.cancel()
        orchestrator.destroy()
    }

    /**
     * In ear test mode only: retry a few times after empty/error, then stop.
     * Unbounded re-arms previously caused Google Speech Recognition to grab/release
     * the mic every ~800ms.
     */
    private fun maybeRearmEarAfterTransientFailure(reason: String) {
        if (!AssistantConfig.isEarTestMode(context)) return
        if (earTestErrorRearms >= EAR_TEST_MAX_ERROR_REARMS) {
            Log.w(TAG, "EAR_TEST_MODE stop re-arm after $earTestErrorRearms failures ($reason)")
            return
        }
        earTestErrorRearms += 1
        scope.launch {
            delay(EAR_TEST_REARM_MS)
            audioManager.startListeningForced()
        }
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
        /** Hard stop for error/empty re-arms in ear test mode (prevents mic thrash). */
        private const val EAR_TEST_MAX_ERROR_REARMS = 2
    }
}

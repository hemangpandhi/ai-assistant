package com.tcs.vehicleassistant.assistant

import com.test.design.assistant.api.AssistantBackend
import com.test.design.assistant.api.AssistantCabinContext
import com.test.design.assistant.api.AssistantMoodId
import com.test.design.assistant.api.AssistantSessionConfig
import com.test.design.assistant.api.AssistantSessionEvent
import com.test.design.assistant.api.AssistantSpeaker
import com.test.design.assistant.api.AssistantSpeechInput
import com.test.design.assistant.api.AssistantStartReason
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Production [AssistantBackend] bridge to [AssistantViewModel] / VehicleAgentService.
 *
 * Compose UI stays decoupled — it only collects [events] and forwards mic via [onSpeechInput].
 * Install via [AssistantRuntime.install] when ready to leave [com.test.design.presentation.assistant.backend.DemoAssistantBackend].
 *
 * Bind a live [AssistantViewModel] with [attachViewModel] once the agent service connects.
 */
class VehicleAgentAssistantBackend(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AssistantBackend {

    private val _events = MutableSharedFlow<AssistantSessionEvent>(extraBufferCapacity = 64)
    override val events: Flow<AssistantSessionEvent> = _events.asSharedFlow()

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var viewModel: AssistantViewModel? = null
    private var collectJob: Job? = null

    fun attachViewModel(vm: AssistantViewModel?) {
        collectJob?.cancel()
        viewModel = vm
        if (vm == null) return
        collectJob = scope.launch {
            vm.uiState.collect { state ->
                when (state) {
                    is AssistantUiState.Idle -> emitMood(AssistantMoodId.Idle)
                    is AssistantUiState.Listening -> {
                        emitMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "",
                                speaker = AssistantSpeaker.User,
                            ),
                        )
                    }
                    is AssistantUiState.Thinking -> emitMood(AssistantMoodId.Thinking)
                    is AssistantUiState.Streaming -> {
                        emitMood(AssistantMoodId.Speaking)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = state.displayText,
                                speaker = AssistantSpeaker.Assistant,
                            ),
                        )
                    }
                    is AssistantUiState.Speaking -> {
                        emitMood(AssistantMoodId.Speaking)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = state.finalMessage,
                                speaker = AssistantSpeaker.Assistant,
                            ),
                        )
                    }
                    is AssistantUiState.Error -> {
                        emitMood(AssistantMoodId.Sad)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = state.errorMessage,
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig,
    ) {
        _sessionActive.value = true
        scope.launch {
            emitMood(AssistantMoodId.Listening)
            _events.emit(
                AssistantSessionEvent.Transcript(
                    text = "Hi, how can I help you?",
                    speaker = AssistantSpeaker.System,
                ),
            )
        }
    }

    override fun stopSession() {
        _sessionActive.value = false
        collectJob?.cancel()
        collectJob = null
    }

    override fun onSpeechInput(input: AssistantSpeechInput) {
        val vm = viewModel ?: return
        when (input) {
            is AssistantSpeechInput.Partial -> {
                scope.launch {
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = input.text,
                            speaker = AssistantSpeaker.User,
                        ),
                    )
                }
            }
            is AssistantSpeechInput.Final -> {
                if (input.text.isNotBlank()) {
                    vm.handleQuery(input.text)
                }
            }
            is AssistantSpeechInput.Rms -> Unit
            AssistantSpeechInput.Hotword -> Unit
        }
    }

    override fun onThumbsFeedback(positive: Boolean) = Unit

    private suspend fun emitMood(mood: AssistantMoodId) {
        _events.emit(AssistantSessionEvent.MoodChanged(mood))
    }
}

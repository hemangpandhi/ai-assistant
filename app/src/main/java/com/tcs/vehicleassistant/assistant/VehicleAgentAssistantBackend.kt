package com.tcs.vehicleassistant.assistant

import android.util.Log
import com.assistant.ui.assistant.api.AssistantBackend
import com.assistant.ui.assistant.api.AssistantCabinContext
import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.AssistantSessionConfig
import com.assistant.ui.assistant.api.AssistantSessionEvent
import com.assistant.ui.assistant.api.AssistantSpeaker
import com.assistant.ui.assistant.api.AssistantSpeechInput
import com.assistant.ui.assistant.api.AssistantStartReason
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.ViewModelEvent
import com.tcs.vehicleassistant.hardware.IAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Production [AssistantBackend] bridge to [AssistantViewModel] / [IAudioManager].
 *
 * Compose stays decoupled: it collects [events] for face/transcript and does not own
 * the mic. Hotword / system-bar show → [startSession] → agent STT → [handleQuery] → TTS.
 */
class VehicleAgentAssistantBackend(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AssistantBackend {

    private val _events = MutableSharedFlow<AssistantSessionEvent>(extraBufferCapacity = 64)
    override val events: Flow<AssistantSessionEvent> = _events.asSharedFlow()

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var viewModel: AssistantViewModel? = null
    private var audioManager: IAudioManager? = null
    private var uiCollectJob: Job? = null
    private var eventCollectJob: Job? = null
    private var listenJob: Job? = null
    private var pendingFinalQuery: String? = null

    /**
     * Bind the live agent once [com.tcs.vehicleassistant.service.VehicleAgentService] connects.
     * Safe to call repeatedly; collectors are replaced.
     *
     * Mic / STT: owned by [IAudioManager] (same path as XML). Compose only renders events.
     */
    fun attachViewModel(vm: AssistantViewModel?, audio: IAudioManager? = null) {
        uiCollectJob?.cancel()
        eventCollectJob?.cancel()
        viewModel = vm
        if (audio != null) {
            audioManager = audio
        } else if (vm == null) {
            audioManager = null
        }
        if (vm == null) return

        uiCollectJob = scope.launch {
            vm.uiState.collect { state -> mapUiState(state) }
        }
        eventCollectJob = scope.launch {
            vm.events.collect { event ->
                when (event) {
                    is ViewModelEvent.StartListening -> startMic(reason = "orchestrator")
                    is ViewModelEvent.SetInputText -> {
                        if (event.text.isNotBlank()) {
                            _events.emit(
                                AssistantSessionEvent.Transcript(
                                    text = event.text,
                                    speaker = AssistantSpeaker.User,
                                ),
                            )
                            emitMood(AssistantMoodId.Listening)
                        }
                    }
                    is ViewModelEvent.FinishSession -> {
                        // Keep immersive stage up; return to listening instead of dismissing.
                        emitMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "Hi, how can I help you?",
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                        startMic(reason = "finish-retry")
                    }
                    else -> Unit
                }
            }
        }

        flushPendingQuery()
        if (_sessionActive.value) {
            startMic(reason = "attach-while-active")
        }
    }

    override fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig,
    ) {
        _sessionActive.value = true
        viewModel?.resetUiState()
        listenJob?.cancel()
        listenJob = scope.launch {
            emitMood(AssistantMoodId.Listening)
            _events.emit(
                AssistantSessionEvent.Transcript(
                    text = when (reason) {
                        AssistantStartReason.Hotword -> "Listening…"
                        else -> "Hi, how can I help you?"
                    },
                    speaker = AssistantSpeaker.System,
                ),
            )
            _events.emit(AssistantSessionEvent.Gaze(x = -0.42f, y = 0.05f))
            // Wait for wake-word AudioRecord to release before binding SpeechRecognizer.
            delay(700)
            if (_sessionActive.value) {
                startMic(reason = "startSession:$reason")
            }
        }
    }

    override fun stopSession() {
        _sessionActive.value = false
        listenJob?.cancel()
        listenJob = null
        runCatching { audioManager?.stopListening() }
        // Destroy is owned by AssistantSession.onHide so wake-word can reclaim cleanly.
    }

    override fun onSpeechInput(input: AssistantSpeechInput) {
        // Production mic is owned by [IAudioManager] via [AssistantViewModel].
        // Compose live-STT is disabled for this backend; keep a fallback path.
        when (input) {
            is AssistantSpeechInput.Partial -> scope.launch {
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = input.text,
                        speaker = AssistantSpeaker.User,
                    ),
                )
                emitMood(AssistantMoodId.Listening)
            }
            is AssistantSpeechInput.Final -> {
                if (input.text.isBlank()) return
                scope.launch {
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = input.text,
                            speaker = AssistantSpeaker.User,
                        ),
                    )
                }
                val vm = viewModel
                if (vm != null) {
                    vm.handleQuery(input.text)
                } else {
                    Log.w(TAG, "Final speech queued — agent ViewModel not bound yet: ${input.text}")
                    pendingFinalQuery = input.text
                }
            }
            is AssistantSpeechInput.Rms -> scope.launch {
                val n = input.normalized.coerceIn(0f, 1f)
                _events.emit(
                    AssistantSessionEvent.Gaze(
                        x = -0.25f - n * 0.25f,
                        y = -0.02f + n * 0.04f,
                    ),
                )
                _events.emit(AssistantSessionEvent.MouthAmplitude((0.15f + n * 0.55f).coerceIn(0f, 1f)))
            }
            AssistantSpeechInput.Hotword -> startMic(reason = "hotword-input")
        }
    }

    override fun onThumbsFeedback(positive: Boolean) = Unit

    /** Explicit mic open used by [AssistantSession] on system-bar / assist flags. */
    fun requestListen() {
        scope.launch {
            if (!_sessionActive.value) {
                _sessionActive.value = true
            }
            // Same wake-word release window as startSession.
            delay(700)
            if (_sessionActive.value) {
                startMic(reason = "session-request")
            }
        }
    }

    private fun flushPendingQuery() {
        val q = pendingFinalQuery ?: return
        val vm = viewModel ?: return
        pendingFinalQuery = null
        vm.handleQuery(q)
    }

    private fun startMic(reason: String) {
        val vm = viewModel
        val audio = audioManager
        if (vm == null || audio == null) {
            Log.d(TAG, "startMic($reason) skipped — agent not bound yet")
            return
        }
        if (vm.isProcessing()) {
            Log.d(TAG, "startMic($reason) skipped — query in flight")
            return
        }
        try {
            audio.stopSpeaking()
            audio.startListening()
            scope.launch { emitMood(AssistantMoodId.Listening) }
            Log.d(TAG, "startMic($reason) ok")
        } catch (t: Throwable) {
            Log.w(TAG, "startMic($reason) failed", t)
            scope.launch {
                emitMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error("Microphone unavailable."))
            }
        }
    }

    private suspend fun mapUiState(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Idle -> {
                emitMood(AssistantMoodId.Idle)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Listening -> {
                emitMood(AssistantMoodId.Listening)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Thinking -> {
                emitMood(AssistantMoodId.Thinking)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = "Thinking…",
                        speaker = AssistantSpeaker.System,
                    ),
                )
            }
            is AssistantUiState.Streaming -> {
                emitMood(AssistantMoodId.Speaking)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = state.displayText,
                        speaker = AssistantSpeaker.Assistant,
                    ),
                )
                _events.emit(AssistantSessionEvent.MouthAmplitude(0.35f))
            }
            is AssistantUiState.Speaking -> {
                emitMood(AssistantMoodId.Speaking)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = state.finalMessage,
                        speaker = AssistantSpeaker.Assistant,
                    ),
                )
                _events.emit(AssistantSessionEvent.MouthAmplitude(0.55f))
            }
            is AssistantUiState.Error -> {
                emitMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error(state.errorMessage))
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
        }
    }

    private suspend fun emitMood(mood: AssistantMoodId) {
        _events.emit(AssistantSessionEvent.MoodChanged(mood))
    }

    companion object {
        private const val TAG = "VehicleAgentBackend"
    }
}

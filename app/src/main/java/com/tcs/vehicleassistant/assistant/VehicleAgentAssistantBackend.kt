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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Production [AssistantBackend] bridge to [AssistantViewModel] / [IAudioManager].
 *
 * Compose collects [events] only. Mic / STT / TTS stay on the agent path (same as XML).
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
    /** True after a successful startListening until stop / result / error. */
    private var micArmed = false

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
                    is ViewModelEvent.StartListening ->
                        scheduleStartMic(reason = "orchestrator", delayMs = 300L, force = true)
                    is ViewModelEvent.SetInputText -> {
                        if (event.text.isNotBlank()) {
                            micArmed = false
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
                        micArmed = false
                        emitMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "Listening…",
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                        scheduleStartMic(reason = "finish-retry", delayMs = 400L, force = true)
                    }
                    else -> Unit
                }
            }
        }

        flushPendingQuery()
        // Only arm mic if nothing is already scheduled/listening — startSession owns the
        // wake-word release delay; a second startListening mid-listen causes BUSY/CLIENT errors.
        if (_sessionActive.value && listenJob?.isActive != true && !micArmed) {
            scheduleStartMic(reason = "attach-while-active", delayMs = 500L)
        }
    }

    override fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig,
    ) {
        _sessionActive.value = true
        micArmed = false
        viewModel?.resetUiState()
        scope.launch {
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
        }
        // One coalesced mic start after wake-word AudioRecord release.
        scheduleStartMic(reason = "startSession:$reason", delayMs = 900L, force = true)
    }

    override fun stopSession() {
        _sessionActive.value = false
        listenJob?.cancel()
        listenJob = null
        micArmed = false
        runCatching { audioManager?.stopListening() }
    }

    override fun onSpeechInput(input: AssistantSpeechInput) {
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
                micArmed = false
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
            AssistantSpeechInput.Hotword ->
                scheduleStartMic(reason = "hotword-input", delayMs = 700L, force = true)
        }
    }

    override fun onThumbsFeedback(positive: Boolean) = Unit

    fun requestListen() {
        if (!_sessionActive.value) {
            _sessionActive.value = true
        }
        // Coalesce with startSession — do not stack a second startListening.
        if (listenJob?.isActive == true || micArmed) {
            Log.d(TAG, "requestListen skipped — mic already scheduled/armed")
            return
        }
        scheduleStartMic(reason = "session-request", delayMs = 900L, force = true)
    }

    private fun scheduleStartMic(
        reason: String,
        delayMs: Long = 0L,
        force: Boolean = false,
    ) {
        listenJob?.cancel()
        listenJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!isActive || !_sessionActive.value) return@launch
            // Retry a few times if the agent service is still binding.
            repeat(8) { attempt ->
                if (!_sessionActive.value) return@launch
                if (startMic(reason = "$reason#$attempt", force = force || attempt == 0)) {
                    return@launch
                }
                delay(250)
            }
            Log.w(TAG, "scheduleStartMic($reason) gave up — agent not bound")
            _events.emit(AssistantSessionEvent.Error("Microphone not ready. Try again."))
        }
    }

    private fun flushPendingQuery() {
        val q = pendingFinalQuery ?: return
        val vm = viewModel ?: return
        pendingFinalQuery = null
        vm.handleQuery(q)
    }

    /** @return true if listening started (or already armed). */
    private fun startMic(reason: String, force: Boolean = false): Boolean {
        val vm = viewModel
        val audio = audioManager
        if (vm == null || audio == null) {
            Log.d(TAG, "startMic($reason) skipped — agent not bound yet")
            return false
        }
        if (vm.isProcessing()) {
            Log.d(TAG, "startMic($reason) skipped — query in flight")
            return true
        }
        if (micArmed && !force) {
            Log.d(TAG, "startMic($reason) skipped — already armed")
            return true
        }
        return try {
            // Clean slate avoids ERROR_RECOGNIZER_BUSY / CLIENT from stacked starts.
            runCatching { audio.stopListening() }
            runCatching { audio.destroySpeechRecognizer() }
            audio.startListening()
            micArmed = true
            scope.launch {
                emitMood(AssistantMoodId.Listening)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = "Listening…",
                        speaker = AssistantSpeaker.System,
                    ),
                )
            }
            Log.d(TAG, "startMic($reason) ok")
            true
        } catch (t: Throwable) {
            micArmed = false
            Log.w(TAG, "startMic($reason) failed", t)
            scope.launch {
                emitMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error("Microphone unavailable."))
            }
            false
        }
    }

    private suspend fun mapUiState(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Idle -> {
                emitMood(AssistantMoodId.Idle)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Listening -> {
                micArmed = true
                emitMood(AssistantMoodId.Listening)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Thinking -> {
                micArmed = false
                emitMood(AssistantMoodId.Thinking)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = "Thinking…",
                        speaker = AssistantSpeaker.System,
                    ),
                )
            }
            is AssistantUiState.Streaming -> {
                micArmed = false
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
                micArmed = false
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
                micArmed = false
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

package com.tcs.vehicleassistant.assistant

import android.util.Log
import com.assistant.ui.assistant.api.AssistantBackend
import com.assistant.ui.assistant.api.AssistantCabinContext
import com.assistant.ui.assistant.api.AssistantDebugLog
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
    private val scope: CoroutineScope = com.tcs.vehicleassistant.core.AgentRuntime.mainScope,
) : AssistantBackend, com.assistant.ui.assistant.api.AssistantMicController {

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
    /** True after onReadyForSpeech until stop / result / error. */
    private var micArmed = false
    private var clientErrorRetries = 0

    /** Throttle assistant transcript / mouth updates to ~30fps. */
    private var lastStreamingUiMs = 0L
    private var lastMouthEmitMs = 0L
    private var lastEmittedTranscript: String? = null

    fun attachViewModel(vm: AssistantViewModel?, audio: IAudioManager? = null) {
        attachSession(vm, audio)
    }

    override fun attachSession(session: Any?, audio: Any?) {
        val vm = session as? AssistantViewModel
        val audioMgr = audio as? IAudioManager
        uiCollectJob?.cancel()
        eventCollectJob?.cancel()
        viewModel = vm
        if (audioMgr != null) {
            audioManager = audioMgr
        } else if (vm == null) {
            audioManager = null
        }
        if (vm == null) {
            AssistantDebugLog.d(TAG, "detach ViewModel")
            return
        }

        AssistantDebugLog.d(TAG, "attach ViewModel + audio")
        uiCollectJob = scope.launch {
            vm.uiState.collect { state -> mapUiState(state) }
        }
        eventCollectJob = scope.launch {
            vm.events.collect { event ->
                when (event) {
                    is ViewModelEvent.StartListening -> {
                        AssistantDebugLog.d(TAG, "event StartListening")
                        scheduleStartMic(reason = "orchestrator", delayMs = MIC_REARM_MS, force = true)
                    }
                    is ViewModelEvent.SetInputText -> {
                        if (event.text.isNotBlank()) {
                            micArmed = false
                            AssistantDebugLog.d(TAG, "user: ${event.text.take(48)}")
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
                        AssistantDebugLog.d(TAG, "event FinishSession → re-arm mic")
                        emitMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "Listening…",
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                        scheduleStartMic(reason = "finish-retry", delayMs = MIC_REARM_MS, force = true)
                    }
                    else -> Unit
                }
            }
        }

        flushPendingQuery()
        if (_sessionActive.value && listenJob?.isActive != true && !micArmed) {
            scheduleStartMic(reason = "attach-while-active", delayMs = MIC_HANDOFF_MS)
        }
    }

    override fun detachSession() {
        attachSession(null, null)
    }

    override fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig,
    ) {
        _sessionActive.value = true
        micArmed = false
        clientErrorRetries = 0
        AssistantDebugLog.clear()
        AssistantDebugLog.d(TAG, "startSession reason=$reason")
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
        // Short handoff wait after wake-word AudioRecord release (was 1400ms).
        scheduleStartMic(reason = "startSession:$reason", delayMs = MIC_HANDOFF_MS, force = true)
    }

    override fun stopSession() {
        AssistantDebugLog.d(TAG, "stopSession")
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
                    AssistantDebugLog.w(TAG, "Final queued — VM unbound: ${input.text}")
                    pendingFinalQuery = input.text
                }
            }
            is AssistantSpeechInput.Rms -> scope.launch {
                val now = System.currentTimeMillis()
                if (now - lastMouthEmitMs < UI_FRAME_MS) return@launch
                lastMouthEmitMs = now
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
                scheduleStartMic(reason = "hotword-input", delayMs = MIC_HANDOFF_MS, force = true)
        }
    }

    override fun onThumbsFeedback(positive: Boolean) = Unit

    override fun requestListen() {
        if (!_sessionActive.value) {
            _sessionActive.value = true
        }
        if (listenJob?.isActive == true || micArmed) {
            AssistantDebugLog.d(TAG, "requestListen skipped (scheduled/armed)")
            return
        }
        AssistantDebugLog.d(TAG, "requestListen")
        scheduleStartMic(reason = "session-request", delayMs = MIC_HANDOFF_MS, force = true)
    }

    private fun scheduleStartMic(
        reason: String,
        delayMs: Long = 0L,
        force: Boolean = false,
    ) {
        listenJob?.cancel()
        listenJob = scope.launch {
            AssistantDebugLog.d(TAG, "mic schedule '$reason' in ${delayMs}ms")
            if (delayMs > 0) delay(delayMs)
            if (!isActive || !_sessionActive.value) return@launch
            repeat(8) { attempt ->
                if (!_sessionActive.value) return@launch
                if (startMic(reason = "$reason#$attempt", force = force || attempt == 0)) {
                    return@launch
                }
                delay(300)
            }
            AssistantDebugLog.w(TAG, "mic schedule gave up — agent unbound")
            _events.emit(AssistantSessionEvent.Error("Microphone not ready. Try again."))
        }
    }

    private fun flushPendingQuery() {
        val q = pendingFinalQuery ?: return
        val vm = viewModel ?: return
        pendingFinalQuery = null
        vm.handleQuery(q)
    }

    /** @return true if startListening was issued (or already armed). */
    private fun startMic(reason: String, force: Boolean = false): Boolean {
        val vm = viewModel
        val audio = audioManager
        if (vm == null || audio == null) {
            AssistantDebugLog.d(TAG, "startMic($reason) wait — unbound")
            return false
        }
        if (vm.isProcessing()) {
            AssistantDebugLog.d(TAG, "startMic($reason) skip — processing")
            return true
        }
        if (micArmed && !force) {
            AssistantDebugLog.d(TAG, "startMic($reason) skip — armed")
            return true
        }
        return try {
            // Do NOT stopListening() then startListening() on the same instance —
            // that is the usual ERROR_CLIENT (5) trigger. Fresh start only.
            if (force && reason.contains("client-retry")) {
                audio.restartListening(delayedMs = MIC_CLIENT_RETRY_MS)
            } else {
                audio.startListening()
            }
            AssistantDebugLog.d(TAG, "startMic($reason) issued")
            true
        } catch (t: Throwable) {
            micArmed = false
            AssistantDebugLog.e(TAG, "startMic($reason) failed: ${t.message}")
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
                clientErrorRetries = 0
                AssistantDebugLog.d(TAG, "ui Listening (ready)")
                emitMood(AssistantMoodId.Listening)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = "Listening…",
                        speaker = AssistantSpeaker.System,
                    ),
                )
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Thinking -> {
                micArmed = false
                AssistantDebugLog.d(TAG, "ui Thinking")
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
                val now = System.currentTimeMillis()
                val textChanged = state.displayText != lastEmittedTranscript
                if (textChanged && now - lastStreamingUiMs >= UI_FRAME_MS) {
                    lastStreamingUiMs = now
                    lastEmittedTranscript = state.displayText
                    AssistantDebugLog.d(TAG, "ui Streaming ${state.displayText.take(40)}")
                    emitMood(AssistantMoodId.Speaking)
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = state.displayText,
                            speaker = AssistantSpeaker.Assistant,
                        ),
                    )
                }
                if (now - lastMouthEmitMs >= UI_FRAME_MS) {
                    lastMouthEmitMs = now
                    // Light amplitude pulse — avoids constant recomposition storms.
                    val pulse = 0.28f + ((now / 80L) % 3) * 0.08f
                    _events.emit(AssistantSessionEvent.MouthAmplitude(pulse))
                }
            }
            is AssistantUiState.Speaking -> {
                micArmed = false
                lastEmittedTranscript = state.finalMessage
                AssistantDebugLog.d(TAG, "ui Speaking ${state.finalMessage.take(40)}")
                emitMood(AssistantMoodId.Speaking)
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = state.finalMessage,
                        speaker = AssistantSpeaker.Assistant,
                    ),
                )
                _events.emit(AssistantSessionEvent.MouthAmplitude(0.5f))
            }
            is AssistantUiState.Error -> {
                micArmed = false
                AssistantDebugLog.e(TAG, "ui Error: ${state.errorMessage}")
                emitMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error(state.errorMessage))
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))

                // CLIENT/BUSY: rebuild recognizer with delay (ERROR_CLIENT=5).
                val msg = state.errorMessage.lowercase()
                val isClient = msg.contains("client") || msg.contains("busy") || msg.contains("(5)")
                if (isClient && clientErrorRetries < 2 && _sessionActive.value) {
                    clientErrorRetries += 1
                    AssistantDebugLog.w(TAG, "ERROR_CLIENT retry #$clientErrorRetries")
                    scheduleStartMic(reason = "client-retry", delayMs = 200L, force = true)
                }
            }
        }
    }

    private suspend fun emitMood(mood: AssistantMoodId) {
        _events.emit(AssistantSessionEvent.MoodChanged(mood))
    }

    companion object {
        private const val TAG = "VehicleAgentBackend"
        /** Wake-word → STT mic handoff (was 1400ms). */
        private const val MIC_HANDOFF_MS = 250L
        /** Re-arm after TTS / turn complete. */
        private const val MIC_REARM_MS = 200L
        /** SpeechRecognizer ERROR_CLIENT rebuild delay. */
        private const val MIC_CLIENT_RETRY_MS = 400L
        private const val UI_FRAME_MS = 32L
    }
}

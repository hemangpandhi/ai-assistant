package com.tcs.vehicleassistant.assistant

import com.assistant.ui.assistant.api.AssistantBackend
import com.assistant.ui.assistant.api.AssistantCabinContext
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.FaceMoodResolver
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
import kotlinx.coroutines.Job
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
 *
 * Scope is resolved on each use — never cache [AgentRuntime.mainScope].
 * [com.tcs.vehicleassistant.service.VehicleAgentService] calls [AgentRuntime.resetForService]
 * on create, which cancels the previous scope; a captured reference would leave mic-arm
 * coroutines dead (no startListening / no live transcript).
 */
class VehicleAgentAssistantBackend(
    private val scopeProvider: () -> CoroutineScope = {
        com.tcs.vehicleassistant.core.AgentRuntime.mainScope
    },
) : AssistantBackend, com.assistant.ui.assistant.api.AssistantMicController {

    private val scope: CoroutineScope
        get() = scopeProvider()

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

    /** Harness turn-taking mood (Listening / Thinking / Speaking / …). */
    private var pipelineMood: AssistantMoodId = AssistantMoodId.Idle
    /** Optional LLM / heuristic emotion (Happy / Sad / …). */
    private var affectiveMood: AssistantMoodId? = null

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
        // Prefer StateFlow for live STT — survives attach races; SharedFlow events can be dropped.
        eventCollectJob = scope.launch {
            launch {
                vm.liveTranscript.collect { text ->
                    if (text.isNotBlank()) {
                        AssistantDebugLog.d(TAG, "live: ${text.take(48)}")
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = text,
                                speaker = AssistantSpeaker.User,
                            ),
                        )
                        setPipelineMood(AssistantMoodId.Listening)
                    }
                }
            }
            vm.events.collect { event ->
                when (event) {
                    is ViewModelEvent.StartListening -> {
                        AssistantDebugLog.d(TAG, "event StartListening")
                        scheduleStartMic(reason = "orchestrator", delayMs = MIC_REARM_MS, force = true)
                    }
                    is ViewModelEvent.SetInputText -> {
                        // liveTranscript collector is primary; keep as fallback for late subscribers.
                        if (event.text.isNotBlank()) {
                            // Do not clear micArmed here — partials arrive while STT is still live.
                            // Clearing it caused attach/requestListen to fight the recognizer.
                            AssistantDebugLog.d(TAG, "user: ${event.text.take(48)}")
                            _events.emit(
                                AssistantSessionEvent.Transcript(
                                    text = event.text,
                                    speaker = AssistantSpeaker.User,
                                ),
                            )
                            setPipelineMood(AssistantMoodId.Listening)
                        }
                    }
                    is ViewModelEvent.FinishSession -> {
                        micArmed = false
                        AssistantDebugLog.d(TAG, "event FinishSession → re-arm mic")
                        setPipelineMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "Listening…",
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                        scheduleStartMic(reason = "finish-retry", delayMs = MIC_REARM_MS, force = true)
                    }
                    is ViewModelEvent.AffectiveMood -> {
                        setAffectiveMood(event.mood)
                    }
                    else -> Unit
                }
            }
        }

        flushPendingQuery()
        // Always (re)arm when a session is live but the ear is not open. Do not trust
        // listenJob?.isActive alone — after AgentRuntime.resetForService that job may
        // belong to a cancelled scope and never complete.
        if (_sessionActive.value &&
            !micArmed &&
            audioManager?.isActivelyListening() != true &&
            !com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isCaptureLive(audioManager)
        ) {
            scheduleStartMic(reason = "attach-while-active", delayMs = 0L)
        } else if (com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isCaptureLive(audioManager) ||
            audioManager?.isActivelyListening() == true
        ) {
            micArmed = true
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
        clientErrorRetries = 0
        AssistantDebugLog.clear()
        AssistantDebugLog.d(TAG, "startSession reason=$reason")

        val alreadyOpen = com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isCaptureLive(audioManager) ||
            audioManager?.isActivelyListening() == true ||
            micArmed

        if (!alreadyOpen) {
            // Avoid wiping Listening UI / re-arming if STT was pre-armed before overlay.
            viewModel?.resetUiState()
            micArmed = false
        } else {
            micArmed = true
            AssistantDebugLog.d(TAG, "startSession — ear already open, skip re-arm")
        }

        scope.launch {
            setPipelineMood(AssistantMoodId.Listening)
            val live = viewModel?.liveTranscript?.value.orEmpty()
            if (live.isBlank()) {
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = when (reason) {
                            AssistantStartReason.Hotword -> "Listening…"
                            else -> "Hi, how can I help you?"
                        },
                        speaker = AssistantSpeaker.System,
                    ),
                )
            } else {
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = live,
                        speaker = AssistantSpeaker.User,
                    ),
                )
            }
            _events.emit(AssistantSessionEvent.Gaze(x = -0.42f, y = 0.05f))
        }
        if (!alreadyOpen) {
            scheduleStartMic(reason = "startSession:$reason", delayMs = 0L, force = false)
        }
    }

    override fun stopSession() {
        AssistantDebugLog.d(TAG, "stopSession")
        _sessionActive.value = false
        listenJob?.cancel()
        listenJob = null
        micArmed = false
        com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
        // Soft stop — keep warm SpeechRecognizer for the next summon.
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
                setPipelineMood(AssistantMoodId.Listening)
            }
            is AssistantSpeechInput.Final -> {
                if (input.text.isBlank()) return
                micArmed = false
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
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
            AssistantSpeechInput.Hotword -> {
                if (!micArmed &&
                    !com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isCaptureLive(audioManager)
                ) {
                    scheduleStartMic(reason = "hotword-input", delayMs = 0L, force = false)
                }
            }
        }
    }

    override fun onThumbsFeedback(positive: Boolean) = Unit

    override fun requestListen() {
        if (!_sessionActive.value) {
            _sessionActive.value = true
        }
        if (listenJob?.isActive == true || micArmed ||
            com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isCaptureLive(audioManager)
        ) {
            micArmed = true
            AssistantDebugLog.d(TAG, "requestListen skipped — already capturing")
            return
        }
        AssistantDebugLog.d(TAG, "requestListen")
        scheduleStartMic(reason = "session-request", delayMs = 0L, force = false)
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
            if (com.tcs.vehicleassistant.WakeWordService.isHoldingMic) {
                AssistantDebugLog.d(TAG, "mic schedule awaiting wake-word release")
                com.tcs.vehicleassistant.WakeWordService.awaitMicReleased(800L)
                delay(25)
            }
            if (!isActive || !_sessionActive.value) return@launch
            repeat(8) { attempt ->
                if (!_sessionActive.value) return@launch
                if (startMic(reason = "$reason#$attempt", force = force || attempt > 0)) {
                    return@launch
                }
                delay(150)
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
            // Barge-in lite: still arm STT while Understand/Act runs.
            AssistantDebugLog.d(TAG, "startMic($reason) while processing — barge-in arm")
        }
        if ((micArmed || audio.isActivelyListening()) && !force) {
            micArmed = true
            AssistantDebugLog.d(TAG, "startMic($reason) skip — already listening")
            return true
        }
        return try {
            audio.ensureWarmRecognizer()
            // Forced starts must actually restart. A plain startListening() no-ops while
            // Starting/Listening and returns "success", leaving the mic unarmed.
            if (force) {
                audio.restartListening(
                    delayedMs = if (reason.contains("client-retry")) {
                        MIC_CLIENT_RETRY_MS
                    } else {
                        MIC_REARM_MS
                    },
                )
            } else {
                // Prefer warm startListening — destroy/recreate only on forced paths.
                audio.startListening()
            }
            micArmed = true
            AssistantDebugLog.d(TAG, "startMic($reason) issued force=$force")
            true
        } catch (t: Throwable) {
            micArmed = false
            AssistantDebugLog.e(TAG, "startMic($reason) failed: ${t.message}")
            scope.launch {
                setPipelineMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error("Microphone unavailable."))
            }
            false
        }
    }

    private suspend fun mapUiState(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Idle -> {
                setPipelineMood(AssistantMoodId.Idle)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Listening -> {
                micArmed = true
                clientErrorRetries = 0
                AssistantDebugLog.d(TAG, "ui Listening (ready)")
                setPipelineMood(AssistantMoodId.Listening)
                // Never clobber live user partials with the placeholder.
                val live = viewModel?.liveTranscript?.value.orEmpty()
                if (live.isBlank()) {
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = "Listening…",
                            speaker = AssistantSpeaker.System,
                        ),
                    )
                }
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Thinking -> {
                // Barge-in lite: keep / re-arm ear during Think — cancelInFlight on speech begin.
                AssistantDebugLog.d(TAG, "ui Thinking (ear open for barge-in)")
                setPipelineMood(AssistantMoodId.Thinking)
                // Keep last user transcript visible while thinking; only show Thinking… if empty.
                val live = viewModel?.liveTranscript?.value.orEmpty()
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = live.ifBlank { "Thinking…" },
                        speaker = if (live.isBlank()) AssistantSpeaker.System else AssistantSpeaker.User,
                    ),
                )
                val audio = audioManager
                if (audio == null || !audio.isActivelyListening()) {
                    scheduleStartMic(reason = "barge-in-think", delayMs = 40L, force = false)
                } else {
                    micArmed = true
                }
            }
            is AssistantUiState.Streaming -> {
                micArmed = false
                val now = System.currentTimeMillis()
                val textChanged = state.displayText != lastEmittedTranscript
                if (textChanged && now - lastStreamingUiMs >= UI_FRAME_MS) {
                    lastStreamingUiMs = now
                    lastEmittedTranscript = state.displayText
                    AssistantDebugLog.d(TAG, "ui Streaming ${state.displayText.take(40)}")
                    setPipelineMood(AssistantMoodId.Speaking)
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
                setPipelineMood(AssistantMoodId.Speaking)
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
                setPipelineMood(AssistantMoodId.Sad)
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

    private suspend fun setPipelineMood(mood: AssistantMoodId) {
        pipelineMood = mood
        // New listen cycle drops prior reply emotion so the ear face stays clear.
        if (mood == AssistantMoodId.Listening) {
            affectiveMood = null
        }
        publishResolvedMood()
    }

    private suspend fun setAffectiveMood(mood: AssistantMoodId) {
        if (!FaceMoodResolver.isAffective(mood)) {
            AssistantDebugLog.d(TAG, "ignore non-affective mood from model: $mood")
            return
        }
        affectiveMood = mood
        AssistantDebugLog.d(TAG, "affective=$mood pipeline=$pipelineMood")
        publishResolvedMood()
    }

    private suspend fun publishResolvedMood() {
        val resolved = FaceMoodResolver.resolve(pipelineMood, affectiveMood)
        _events.emit(AssistantSessionEvent.MoodChanged(resolved))
    }

    companion object {
        private const val TAG = "VehicleAgentBackend"
        /** Settle after confirmed wake-word mic release (was 250–1400ms blind delay). */
        private const val MIC_POST_RELEASE_MS = 60L
        /** Legacy blind handoff when release was not awaited. */
        private const val MIC_HANDOFF_MS = 120L
        /** Re-arm after TTS / turn complete. */
        private const val MIC_REARM_MS = 80L
        /** SpeechRecognizer ERROR_CLIENT rebuild delay. */
        private const val MIC_CLIENT_RETRY_MS = 400L
        private const val UI_FRAME_MS = 32L
    }
}

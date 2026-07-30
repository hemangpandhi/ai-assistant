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
import com.tcs.vehicleassistant.controller.UiUxAssistantViewModel
import com.tcs.vehicleassistant.controller.UiUxViewModelEvent
import com.tcs.vehicleassistant.hardware.SessionAudioPort
import com.tcs.vehicleassistant.wakeword.UiUxWakeWordService
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
 * Production [AssistantBackend] bridge to [UiUxAssistantViewModel] / [SessionAudioPort].
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

    private var viewModel: UiUxAssistantViewModel? = null
    private var audioManager: SessionAudioPort? = null
    private var uiCollectJob: Job? = null
    private var eventCollectJob: Job? = null
    private var listenJob: Job? = null
    private var pendingFinalQuery: String? = null
    /** True after onReadyForSpeech until stop / result / error. */
    private var micArmed = false
    private var clientErrorRetries = 0
    /** Bumps on start/stop session so stale listen jobs / TTS finals are ignored. */
    private var micGeneration = 0
    /** Edge-trigger STT soft-stop once per busy turn (Thinking/Streaming/Speaking). */
    private var sttStoppedForBusyTurn = false
    private var lastMappedUi: String = "Idle"

    /** Throttle assistant transcript / mouth updates to ~30fps. */
    private var lastStreamingUiMs = 0L
    private var lastMouthEmitMs = 0L
    private var lastEmittedTranscript: String? = null
    /** Polls TTS spoken length so Compose shows words as they are said. */
    private var speakRevealJob: Job? = null
    private var speakRevealStartedAtMs = 0L

    /** Harness turn-taking mood (Listening / Thinking / Speaking / …). */
    private var pipelineMood: AssistantMoodId = AssistantMoodId.Idle
    /** Optional LLM / heuristic emotion (Happy / Sad / …). */
    private var affectiveMood: AssistantMoodId? = null

    fun attachViewModel(vm: UiUxAssistantViewModel?, audio: SessionAudioPort? = null) {
        attachSession(vm, audio)
    }

    override fun attachSession(session: Any?, audio: Any?) {
        val vm = session as? UiUxAssistantViewModel
        val audioMgr = audio as? SessionAudioPort
        uiCollectJob?.cancel()
        eventCollectJob?.cancel()
        stopSpeakReveal()
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
                    is UiUxViewModelEvent.StartListening -> {
                        AssistantDebugLog.d(TAG, "event StartListening")
                        // Soft re-arm — do not destroy a healthy recognizer (force only on ERROR_CLIENT).
                        scheduleStartMic(reason = "orchestrator", delayMs = MIC_REARM_MS, force = false)
                    }
                    is UiUxViewModelEvent.SetInputText -> {
                        // liveTranscript collector is primary; keep as fallback for late subscribers.
                        if (event.text.isNotBlank()) {
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
                    is UiUxViewModelEvent.FinishSession -> {
                        micArmed = false
                        com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
                        AssistantDebugLog.d(TAG, "event FinishSession → re-arm mic")
                        setPipelineMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "Listening…",
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                        scheduleStartMic(reason = "finish-retry", delayMs = MIC_REARM_MS, force = false)
                    }
                    is UiUxViewModelEvent.AffectiveMood -> {
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
            audioManager?.isActivelyListening() != true
        ) {
            scheduleStartMic(reason = "attach-while-active", delayMs = 0L)
        } else if (audioManager?.isReadyListening() == true) {
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
        micGeneration++
        sttStoppedForBusyTurn = false
        _sessionActive.value = true
        clientErrorRetries = 0
        AssistantDebugLog.clear()
        AssistantDebugLog.d(TAG, "startSession reason=$reason gen=$micGeneration")

        val alreadyReady = audioManager?.isReadyListening() == true
        if (alreadyReady) {
            micArmed = true
            AssistantDebugLog.d(TAG, "startSession — already ready")
        } else {
            micArmed = false
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
        // Sole owner of startListening — one arm per session start.
        if (!alreadyReady) {
            scheduleStartMic(reason = "startSession:$reason", delayMs = 0L, force = false)
        }
    }

    override fun stopSession() {
        if (!_sessionActive.value && listenJob == null && !micArmed) {
            AssistantDebugLog.d(TAG, "stopSession noop")
            return
        }
        AssistantDebugLog.d(TAG, "stopSession gen=$micGeneration")
        micGeneration++
        _sessionActive.value = false
        listenJob?.cancel()
        listenJob = null
        micArmed = false
        sttStoppedForBusyTurn = false
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
        if (audioManager?.isReadyListening() == true) {
            micArmed = true
            AssistantDebugLog.d(TAG, "requestListen skipped — already ready")
            return
        }
        if (listenJob?.isActive == true || audioManager?.isActivelyListening() == true) {
            AssistantDebugLog.d(TAG, "requestListen skipped — start in flight")
            return
        }
        // Stale coordinator/micArmed must not suppress a real re-arm.
        micArmed = false
        AssistantDebugLog.d(TAG, "requestListen")
        scheduleStartMic(reason = "session-request", delayMs = 0L, force = false)
    }

    private fun scheduleStartMic(
        reason: String,
        delayMs: Long = 0L,
        force: Boolean = false,
    ) {
        if (!force && listenJob?.isActive == true) {
            AssistantDebugLog.d(TAG, "mic schedule coalesce '$reason'")
            return
        }
        if (!force && audioManager?.isReadyListening() == true) {
            micArmed = true
            return
        }
        val gen = micGeneration
        listenJob?.cancel()
        listenJob = scope.launch {
            AssistantDebugLog.d(TAG, "mic schedule '$reason' in ${delayMs}ms force=$force gen=$gen")
            if (delayMs > 0) delay(delayMs)
            if (!isActive || !_sessionActive.value || gen != micGeneration) return@launch

            // Wait for Vosk to actually release — never start STT while it holds AudioRecord.
            if (UiUxWakeWordService.isHoldingMic) {
                AssistantDebugLog.d(TAG, "await wake-word release")
                var attempts = 0
                while (
                    isActive &&
                    _sessionActive.value &&
                    gen == micGeneration &&
                    UiUxWakeWordService.isHoldingMic &&
                    attempts < 6
                ) {
                    UiUxWakeWordService.awaitMicReleased(400L)
                    if (!UiUxWakeWordService.isHoldingMic) break
                    attempts++
                    delay(100)
                }
                if (UiUxWakeWordService.isHoldingMic) {
                    AssistantDebugLog.w(TAG, "wake still holding — defer arm ($reason)")
                    if (_sessionActive.value && gen == micGeneration) {
                        // Soft UI; schedule a single delayed retry outside this job.
                        setPipelineMood(AssistantMoodId.Listening)
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = "Listening…",
                                speaker = AssistantSpeaker.System,
                            ),
                        )
                        listenJob = null
                        scheduleStartMic(
                            reason = "$reason-wake-retry",
                            delayMs = 500L,
                            force = false,
                        )
                    }
                    return@launch
                }
                delay(80)
            }
            if (!isActive || !_sessionActive.value || gen != micGeneration) return@launch

            // Let preArm finish attach + ensureWarmRecognizer before we startListening.
            if (com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isWarmingUp()) {
                AssistantDebugLog.d(TAG, "await preArm warm")
                val warmDeadline = System.currentTimeMillis() + 1200L
                while (
                    isActive &&
                    _sessionActive.value &&
                    gen == micGeneration &&
                    com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isWarmingUp() &&
                    System.currentTimeMillis() < warmDeadline
                ) {
                    delay(40)
                }
            }
            if (!isActive || !_sessionActive.value || gen != micGeneration) return@launch

            if (audioManager?.isReadyListening() == true) {
                micArmed = true
                clientErrorRetries = 0
                return@launch
            }

            // One start (or one forced recreate).
            if (force) {
                startMic(reason = reason, force = true)
            } else if (audioManager?.isActivelyListening() != true) {
                startMic(reason = reason, force = false)
            }

            // Wait for Ready — includes deferred settle/coalesce inside AudioManager.
            val deadline = System.currentTimeMillis() + READY_WAIT_MS
            while (
                isActive &&
                _sessionActive.value &&
                gen == micGeneration &&
                System.currentTimeMillis() < deadline
            ) {
                if (audioManager?.isReadyListening() == true) {
                    micArmed = true
                    clientErrorRetries = 0
                    AssistantDebugLog.d(TAG, "mic ready ($reason)")
                    return@launch
                }
                delay(50)
            }

            // Single recovery only.
            if (!_sessionActive.value || gen != micGeneration ||
                audioManager?.isReadyListening() == true
            ) {
                micArmed = audioManager?.isReadyListening() == true
                return@launch
            }
            AssistantDebugLog.w(TAG, "mic not ready — one recreate ($reason)")
            startMic(reason = "$reason-recovery", force = true)
            val recoveryDeadline = System.currentTimeMillis() + READY_WAIT_MS
            while (
                isActive &&
                _sessionActive.value &&
                gen == micGeneration &&
                System.currentTimeMillis() < recoveryDeadline
            ) {
                if (audioManager?.isReadyListening() == true) {
                    micArmed = true
                    clientErrorRetries = 0
                    AssistantDebugLog.d(TAG, "mic ready after recovery ($reason)")
                    return@launch
                }
                delay(50)
            }

            if (!_sessionActive.value || gen != micGeneration) return@launch
            // Soft keep-alive + one slow retry — never flash "Microphone not ready".
            AssistantDebugLog.w(TAG, "mic arm incomplete — keep Listening UI + slow retry")
            setPipelineMood(AssistantMoodId.Listening)
            _events.emit(
                AssistantSessionEvent.Transcript(
                    text = "Listening…",
                    speaker = AssistantSpeaker.System,
                ),
            )
            if (clientErrorRetries < 2) {
                clientErrorRetries++
                listenJob = null
                scheduleStartMic(
                    reason = "$reason-slow-retry",
                    delayMs = 900L,
                    force = true,
                )
            }
        }
    }

    private fun flushPendingQuery() {
        val q = pendingFinalQuery ?: return
        val vm = viewModel ?: return
        pendingFinalQuery = null
        vm.handleQuery(q)
    }

    /** @return true if startListening/restart was issued (or already ready/starting). */
    private fun startMic(reason: String, force: Boolean = false): Boolean {
        val vm = viewModel
        val audio = audioManager
        if (vm == null || audio == null) {
            AssistantDebugLog.d(TAG, "startMic($reason) wait — unbound")
            return false
        }
        if (audio.isReadyListening()) {
            micArmed = true
            AssistantDebugLog.d(TAG, "startMic($reason) skip — already ready")
            return true
        }
        if (!force && audio.isActivelyListening()) {
            AssistantDebugLog.d(TAG, "startMic($reason) skip — already starting")
            return true
        }
        return try {
            audio.ensureWarmRecognizer()
            if (force && reason.contains("client-retry")) {
                audio.restartListening(delayedMs = MIC_CLIENT_RETRY_MS)
            } else {
                // Prefer warm startListening — destroy/recreate only on client-retry.
                audio.startListening()
            }
            micArmed = true
            AssistantDebugLog.d(TAG, "startMic($reason) issued")
            true
        } catch (t: Throwable) {
            micArmed = false
            com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
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
                lastMappedUi = "Idle"
                stopSpeakReveal()
                setPipelineMood(AssistantMoodId.Idle)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
            }
            is AssistantUiState.Listening -> {
                lastMappedUi = "Listening"
                stopSpeakReveal()
                // Only treat as armed when STT is actually ready — VM may set Listening early.
                if (audioManager?.isReadyListening() == true) {
                    micArmed = true
                    sttStoppedForBusyTurn = false
                    clientErrorRetries = 0
                    AssistantDebugLog.d(TAG, "ui Listening (ready)")
                } else {
                    micArmed = false
                    AssistantDebugLog.d(TAG, "ui Listening (arming)")
                    if (_sessionActive.value &&
                        listenJob?.isActive != true &&
                        audioManager?.isActivelyListening() != true
                    ) {
                        scheduleStartMic(reason = "ui-listening", delayMs = 0L, force = false)
                    }
                }
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
                // Close STT on entry — cancel any soft-miss re-arm racing into Think.
                if (lastMappedUi != "Thinking") {
                    micArmed = false
                    listenJob?.cancel()
                    listenJob = null
                    if (audioManager?.isActivelyListening() == true ||
                        audioManager?.isReadyListening() == true
                    ) {
                        runCatching { audioManager?.stopListening() }
                    }
                    sttStoppedForBusyTurn = true
                }
                lastMappedUi = "Thinking"
                stopSpeakReveal()
                AssistantDebugLog.d(TAG, "ui Thinking")
                setPipelineMood(AssistantMoodId.Thinking)
                val live = viewModel?.liveTranscript?.value.orEmpty()
                _events.emit(
                    AssistantSessionEvent.Transcript(
                        text = live.ifBlank { "Thinking…" },
                        speaker = if (live.isBlank()) AssistantSpeaker.System else AssistantSpeaker.User,
                    ),
                )
            }
            is AssistantUiState.Streaming -> {
                micArmed = false
                if (!sttStoppedForBusyTurn) {
                    listenJob?.cancel()
                    listenJob = null
                    runCatching { audioManager?.stopListening() }
                    sttStoppedForBusyTurn = true
                }
                lastMappedUi = "Streaming"
                AssistantDebugLog.d(TAG, "ui Streaming ${state.displayText.take(40)}")
                setPipelineMood(AssistantMoodId.Speaking)
                ensureSpeakReveal()
                val now = System.currentTimeMillis()
                if (now - lastMouthEmitMs >= UI_FRAME_MS) {
                    lastMouthEmitMs = now
                    val pulse = 0.28f + ((now / 80L) % 3) * 0.08f
                    _events.emit(AssistantSessionEvent.MouthAmplitude(pulse))
                }
            }
            is AssistantUiState.Speaking -> {
                micArmed = false
                if (!sttStoppedForBusyTurn) {
                    listenJob?.cancel()
                    listenJob = null
                    runCatching { audioManager?.stopListening() }
                    sttStoppedForBusyTurn = true
                }
                lastMappedUi = "Speaking"
                AssistantDebugLog.d(TAG, "ui Speaking ${state.finalMessage.take(40)}")
                setPipelineMood(AssistantMoodId.Speaking)
                ensureSpeakReveal()
                _events.emit(AssistantSessionEvent.MouthAmplitude(0.5f))
            }
            is AssistantUiState.Error -> {
                lastMappedUi = "Error"
                stopSpeakReveal()
                micArmed = false
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
                AssistantDebugLog.e(TAG, "ui Error: ${state.errorMessage}")
                setPipelineMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error(state.errorMessage))
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))

                val msg = state.errorMessage.lowercase()
                val isBusy = msg.contains("busy") || msg.contains("(8)")
                val isClient = msg.contains("client") || msg.contains("(5)") ||
                    msg.contains("error_client")
                if ((isBusy || isClient) && clientErrorRetries < 3 && _sessionActive.value) {
                    // Don't stack retries on top of an active schedule.
                    if (listenJob?.isActive == true) {
                        AssistantDebugLog.d(TAG, "recoverable error — schedule already active")
                        return
                    }
                    clientErrorRetries += 1
                    val delayMs = if (isBusy) MIC_BUSY_RETRY_MS else MIC_CLIENT_RETRY_MS
                    val reason = if (isBusy) "busy-retry" else "client-retry"
                    AssistantDebugLog.w(TAG, "$reason #$clientErrorRetries delay=${delayMs}ms")
                    scheduleStartMic(reason = reason, delayMs = delayMs, force = true)
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

    private fun stopSpeakReveal() {
        speakRevealJob?.cancel()
        speakRevealJob = null
        speakRevealStartedAtMs = 0L
    }

    /**
     * Emit assistant transcript word-by-word, advanced by TTS range callbacks
     * (with a speech-rate fallback when ranges are missing).
     */
    private fun ensureSpeakReveal() {
        if (speakRevealJob?.isActive == true) return
        speakRevealStartedAtMs = System.currentTimeMillis()
        val gen = micGeneration
        speakRevealJob = scope.launch {
            while (isActive &&
                micGeneration == gen &&
                (lastMappedUi == "Streaming" || lastMappedUi == "Speaking")
            ) {
                val full = when (val state = viewModel?.uiState?.value) {
                    is AssistantUiState.Streaming -> state.displayText
                    is AssistantUiState.Speaking -> state.finalMessage
                    else -> break
                }
                if (full.isBlank()) {
                    delay(UI_FRAME_MS)
                    continue
                }
                val reported = viewModel?.ttsSpokenLength ?: 0
                val elapsedMs = System.currentTimeMillis() - speakRevealStartedAtMs
                val simulated = ((elapsedMs / 1000f) * SPEAK_REVEAL_CHARS_PER_SEC).toInt()
                val effective = maxOf(reported, simulated).coerceIn(0, full.length)
                val visible = spokenTranscriptPrefix(full, effective)
                val now = System.currentTimeMillis()
                if (visible.isNotEmpty() &&
                    visible != lastEmittedTranscript &&
                    now - lastStreamingUiMs >= UI_FRAME_MS
                ) {
                    lastStreamingUiMs = now
                    lastEmittedTranscript = visible
                    AssistantDebugLog.d(TAG, "speakReveal ${visible.take(48)}")
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = visible,
                            speaker = AssistantSpeaker.Assistant,
                        ),
                    )
                }
                if (lastMappedUi == "Speaking" && effective >= full.length) {
                    if (lastEmittedTranscript != full) {
                        lastEmittedTranscript = full
                        _events.emit(
                            AssistantSessionEvent.Transcript(
                                text = full,
                                speaker = AssistantSpeaker.Assistant,
                            ),
                        )
                    }
                    break
                }
                delay(UI_FRAME_MS)
            }
        }
    }

    companion object {
        private const val TAG = "VehicleAgentBackend"
        /** Re-arm after TTS / turn complete. */
        private const val MIC_REARM_MS = 200L
        /** SpeechRecognizer ERROR_CLIENT rebuild delay. */
        private const val MIC_CLIENT_RETRY_MS = 600L
        /** ERROR_RECOGNIZER_BUSY needs a longer cool-down. */
        private const val MIC_BUSY_RETRY_MS = 900L
        /** Wait for onReadyForSpeech (includes AudioManager settle / ready-watchdog). */
        private const val READY_WAIT_MS = 4500L
        private const val UI_FRAME_MS = 32L
        /** Fallback reveal pace when TTS onRangeStart is unavailable (~speech rate). */
        private const val SPEAK_REVEAL_CHARS_PER_SEC = 18f
    }
}

/**
 * Visible assistant transcript up to [spokenChars], snapped forward to a word boundary
 * so the current spoken word is shown whole.
 */
internal fun spokenTranscriptPrefix(full: String, spokenChars: Int): String {
    if (full.isEmpty() || spokenChars <= 0) return ""
    if (spokenChars >= full.length) return full
    var end = spokenChars
    while (end < full.length && !full[end].isWhitespace()) {
        end++
    }
    return full.substring(0, end)
}

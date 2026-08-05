package com.tcs.vehicleassistant.assistant

import android.content.Context
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
import com.assistant.api.face.PendingToolFaceCues
import com.assistant.ui.assistant.api.FaceCueParser
import com.assistant.ui.assistant.api.FaceMoodResolver
import com.assistant.ui.assistant.api.MoodTagParser
import com.assistant.ui.assistant.api.ToolFaceCues
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.ViewModelEvent
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.hardware.IAudioManager
import java.io.File
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
) : AssistantBackend, com.assistant.ui.assistant.api.AssistantMicController {

    private val _events = MutableSharedFlow<AssistantSessionEvent>(extraBufferCapacity = 64)
    override val events: Flow<AssistantSessionEvent> = _events.asSharedFlow()

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var viewModel: AssistantViewModel? = null
    private var audioManager: IAudioManager? = null
    private var appContext: Context? = null
    private var uiCollectJob: Job? = null
    private var eventCollectJob: Job? = null
    private var listenJob: Job? = null
    private var sttDismissJob: Job? = null
    private var pendingFinalQuery: String? = null
    /** True after onReadyForSpeech until stop / result / error. */
    private var micArmed = false
    private var clientErrorRetries = 0
    /** Latched once we know Whisper sideloads are absent — skip fruitless STT retries. */
    private var sherpaModelsMissing = false
    /**
     * Wall-clock start of the current listen window (session open or continue-turn).
     * Used so early empty / no-match STT does not say "I didn't catch that" before ~5s.
     */
    private var listenWindowStartedAtMs = 0L

    /** Harness turn-taking mood (Listening / Thinking / Speaking / …). */
    private var pipelineMood: AssistantMoodId = AssistantMoodId.Idle
    /** Optional LLM `<mood>` affective tint from conversation context. */
    private var affectiveMood: AssistantMoodId? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    fun attachViewModel(
        vm: AssistantViewModel?,
        audio: IAudioManager? = null,
        context: Context? = null,
    ) {
        if (context != null) {
            bindContext(context)
        }
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
        audioManager?.prewarmEar()
        uiCollectJob = scope.launch {
            vm.uiState.collect { state -> mapUiState(state) }
        }
        eventCollectJob = scope.launch {
            vm.events.collect { event ->
                when (sessionTurnPolicyFor(event)) {
                    SessionTurnPolicy.Continue -> {
                        AssistantDebugLog.d(TAG, "event StartListening")
                        markListenWindowStart()
                        scheduleStartMic(reason = "orchestrator", delayMs = 350L, force = true)
                    }
                    SessionTurnPolicy.Complete -> {
                        // Agent says turn is done (e.g. play music) — dismiss overlay.
                        // Continue turns use StartListening (wellness offer / question).
                        AssistantDebugLog.d(TAG, "event FinishSession → SessionComplete")
                        stopSession()
                        emitPipelineMood(AssistantMoodId.Idle)
                        _events.emit(AssistantSessionEvent.SessionComplete)
                    }
                    null -> when (event) {
                        is ViewModelEvent.SetInputText -> {
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
                                emitPipelineMood(AssistantMoodId.Listening)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        flushPendingQuery()
        if (_sessionActive.value && listenJob?.isActive != true && !micArmed) {
            scheduleStartMic(reason = "attach-while-active", delayMs = 600L)
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
        sherpaModelsMissing = false
        markListenWindowStart()
        sttDismissJob?.cancel()
        sttDismissJob = null
        AssistantDebugLog.clear()
        AssistantDebugLog.d(TAG, "startSession reason=$reason")
        viewModel?.processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.Cancel)
        scope.launch {
            emitPipelineMood(AssistantMoodId.Listening)
            // No status captions ("Listening…") — stage stays blank until STT partials.
            _events.emit(AssistantSessionEvent.Gaze(x = -0.42f, y = 0.05f))
        }
        // Open the ear ASAP. Wake-word AudioRecord stop needs a short settle only;
        // the old 1.4s wait made press-to-listen feel broken vs Gemini.
        scheduleStartMic(
            reason = "startSession:$reason",
            delayMs = MIC_OPEN_DELAY_MS,
            force = true,
        )
    }

    override fun stopSession() {
        AssistantDebugLog.d(TAG, "stopSession")
        _sessionActive.value = false
        listenJob?.cancel()
        listenJob = null
        sttDismissJob?.cancel()
        sttDismissJob = null
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
                emitPipelineMood(AssistantMoodId.Listening)
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
                    vm.processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.ProcessQuery(input.text))
                } else {
                    AssistantDebugLog.w(TAG, "Final queued — VM unbound: ${input.text}")
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
            AssistantSpeechInput.Hotword -> {
                markListenWindowStart()
                scheduleStartMic(reason = "hotword-input", delayMs = MIC_OPEN_DELAY_MS, force = true)
            }
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
        audioManager?.prewarmEar()
        markListenWindowStart()
        scheduleStartMic(reason = "session-request", delayMs = MIC_OPEN_DELAY_MS, force = true)
    }

    private fun markListenWindowStart() {
        listenWindowStartedAtMs = System.currentTimeMillis()
    }

    private fun listenElapsedMs(): Long {
        val started = listenWindowStartedAtMs
        if (started <= 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - started).coerceAtLeast(0L)
    }

    private fun scheduleStartMic(
        reason: String,
        delayMs: Long = 0L,
        force: Boolean = false,
        rebuildRecognizer: Boolean = false,
    ) {
        listenJob?.cancel()
        listenJob = scope.launch {
            AssistantDebugLog.d(TAG, "mic schedule '$reason' in ${delayMs}ms rebuild=$rebuildRecognizer")
            if (delayMs > 0) delay(delayMs)
            if (!isActive || !_sessionActive.value) return@launch
            repeat(8) { attempt ->
                if (!_sessionActive.value) return@launch
                if (
                    startMic(
                        reason = "$reason#$attempt",
                        force = force || attempt == 0,
                        rebuildRecognizer = rebuildRecognizer && attempt == 0,
                    )
                ) {
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
        vm.processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.ProcessQuery(q))
    }

    /** @return true if startListening was issued (or already armed). */
    private suspend fun startMic(
        reason: String,
        force: Boolean = false,
        rebuildRecognizer: Boolean = false,
    ): Boolean {
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
        // Only gate on Whisper sideloads when prefs explicitly select Sherpa.
        // If context/prefs are not ready yet, do not assume Sherpa — Google may be selected.
        if (usesSherpaEngine() == true && !sherpaModelsPresent()) {
            sherpaModelsMissing = true
            micArmed = false
            AssistantDebugLog.e(
                TAG,
                "startMic($reason) blocked — Whisper STT missing under " +
                    AssistantConfig.Audio.STT_SIDELOAD_DIR,
            )
            emitPipelineMood(AssistantMoodId.Sad)
            _events.emit(AssistantSessionEvent.Error(STT_MODELS_MISSING_MSG))
            return true
        }
        return try {
            // Forced starts must actually restart via startListeningForced (ear cancels
            // in-flight capture). Prefer stop-only before rebuild: destroySpeechRecognizer()
            // tears down Sherpa and races AudioRecord release.
            if (force) {
                val wasArmed = micArmed
                if (!rebuildRecognizer) {
                    audio.startListeningForced()
                    AssistantDebugLog.d(TAG, "startMic($reason) forced")
                    return true
                }
                runCatching { audio.stopListening() }
                runCatching { audio.destroySpeechRecognizer() }
                audio.prewarmEar()
                if (wasArmed || rebuildRecognizer) {
                    delay(if (rebuildRecognizer) MIC_REBUILD_SETTLE_MS else MIC_RESTART_SETTLE_MS)
                }
                audio.startListeningForced()
            } else {
                audio.startListening()
            }
            AssistantDebugLog.d(
                TAG,
                "startMic($reason) issued force=$force rebuild=$rebuildRecognizer",
            )
            true
        } catch (t: Throwable) {
            micArmed = false
            AssistantDebugLog.e(TAG, "startMic($reason) failed: ${t.message}")
            scope.launch {
                emitPipelineMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error("Microphone unavailable."))
            }
            false
        }
    }

    private suspend fun mapUiState(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Idle -> {
                PendingToolFaceCues.clear()
                emitPipelineMood(AssistantMoodId.Idle)
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
                _events.emit(AssistantSessionEvent.FaceCuesChanged(null))
            }
            is AssistantUiState.Listening -> {
                micArmed = true
                clientErrorRetries = 0
                sttDismissJob?.cancel()
                sttDismissJob = null
                AssistantDebugLog.d(
                    TAG,
                    "ui Listening partial='${state.partialText.take(48)}'",
                )
                // Fresh turn — drop prior LLM face tint until the next reply.
                affectiveMood = null
                PendingToolFaceCues.clear()
                emitPipelineMood(AssistantMoodId.Listening)
                // Live captions only — never clobber with "Listening…".
                // Blank ready-for-speech keeps the prior transcript (or empty stage).
                if (state.partialText.isNotBlank()) {
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = state.partialText,
                            speaker = AssistantSpeaker.User,
                        ),
                    )
                }
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))
                _events.emit(AssistantSessionEvent.FaceCuesChanged(null))
            }
            is AssistantUiState.Thinking -> {
                micArmed = false
                AssistantDebugLog.d(TAG, "ui Thinking")
                emitPipelineMood(AssistantMoodId.Thinking)
                // Keep the user's last utterance visible — no "Thinking…" placeholder.
                val query = state.userQuery?.trim().orEmpty()
                if (query.isNotBlank()) {
                    _events.emit(
                        AssistantSessionEvent.Transcript(
                            text = query,
                            speaker = AssistantSpeaker.User,
                        ),
                    )
                }
            }
            is AssistantUiState.Streaming -> {
                micArmed = false
                AssistantDebugLog.d(TAG, "ui Streaming ${state.displayText.take(40)}")
                emitPipelineMood(AssistantMoodId.Speaking)
                emitAssistantText(state.displayText, mouthAmplitude = 0.35f)
            }
            is AssistantUiState.Speaking -> {
                micArmed = false
                AssistantDebugLog.d(TAG, "ui Speaking ${state.finalMessage.take(40)}")
                emitPipelineMood(AssistantMoodId.Speaking)
                emitAssistantText(state.finalMessage, mouthAmplitude = 0.55f)
            }
            is AssistantUiState.Error -> {
                micArmed = false
                val raw = state.errorMessage
                val msg = raw.lowercase()
                val missingModels = sherpaModelsMissing ||
                    (msg.contains("unknown recognition") &&
                        usesSherpaEngine() == true &&
                        !sherpaModelsPresent())
                if (missingModels) {
                    sherpaModelsMissing = true
                }
                val elapsed = listenElapsedMs()
                val policy = sttErrorPolicyFor(
                    raw,
                    missingModels = missingModels,
                    retryCount = clientErrorRetries,
                    listenElapsedMs = elapsed,
                )
                when (policy) {
                    SttErrorPolicy.Hold -> {
                        val display = when {
                            missingModels -> STT_MODELS_MISSING_MSG
                            else -> friendlySttErrorMessage(raw)
                        }
                        AssistantDebugLog.e(TAG, "ui Error hold: $display (raw=$raw)")
                        emitPipelineMood(AssistantMoodId.Sad)
                        _events.emit(AssistantSessionEvent.Error(display))
                        _events.emit(AssistantSessionEvent.MouthAmplitude(null))
                    }
                    SttErrorPolicy.RetryQuiet -> {
                        if (!_sessionActive.value) return
                        val remaining =
                            AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS - elapsed
                        AssistantDebugLog.d(
                            TAG,
                            "STT quiet re-arm (${elapsed}ms into listen window, " +
                                "remaining≈${remaining}ms) after: $raw",
                        )
                        emitPipelineMood(AssistantMoodId.Listening)
                        _events.emit(AssistantSessionEvent.MouthAmplitude(null))
                        scheduleStartMic(
                            reason = "stt-listen-window",
                            delayMs = 120L,
                            force = true,
                        )
                    }
                    SttErrorPolicy.Retry -> {
                        if (!_sessionActive.value) return
                        clientErrorRetries += 1
                        AssistantDebugLog.w(
                            TAG,
                            "STT retry #$clientErrorRetries after: $raw",
                        )
                        emitPipelineMood(AssistantMoodId.Listening)
                        _events.emit(AssistantSessionEvent.MouthAmplitude(null))
                        scheduleStartMic(
                            reason = "stt-retry",
                            delayMs = 350L,
                            force = true,
                            rebuildRecognizer = true,
                        )
                    }
                    SttErrorPolicy.Complete -> {
                        val display = when {
                            missingModels -> STT_MODELS_MISSING_MSG
                            else -> friendlySttErrorMessage(raw)
                        }
                        AssistantDebugLog.e(TAG, "ui Error: $display (raw=$raw)")
                        emitPipelineMood(AssistantMoodId.Sad)
                        _events.emit(AssistantSessionEvent.Error(display))
                        _events.emit(AssistantSessionEvent.MouthAmplitude(null))
                        val earTest = appContext?.let { AssistantConfig.isEarTestMode(it) } == true
                        if (earTest) {
                            // Keep overlay open; ViewModel re-arms the ear for bring-up.
                            AssistantDebugLog.d(TAG, "EAR_TEST_MODE — skip STT dismiss")
                        } else {
                            // Immersive has no mic-retry affordance — dismiss shortly.
                            scheduleSttErrorDismiss(raw)
                        }
                    }
                }
            }
        }
    }

    /**
     * @return true = Sherpa, false = Google, null = prefs/context unavailable
     * (do not assume Sherpa — settings may already be Google).
     */
    private fun usesSherpaEngine(): Boolean? {
        val ctx = appContext ?: return null
        return !AssistantConfig.prefersGoogleStt(ctx)
    }

    private fun sherpaModelsPresent(): Boolean {
        val dir = File(AssistantConfig.Audio.STT_SIDELOAD_DIR)
        fun triplet(prefix: String): Boolean =
            listOf(
                "$prefix-encoder.int8.onnx",
                "$prefix-decoder.int8.onnx",
                "$prefix-tokens.txt",
            ).all { name ->
                File(dir, name).let { it.exists() && it.canRead() && it.length() > 0L }
            }
        return triplet("base.en") || triplet("tiny.en")
    }

    private fun scheduleSttErrorDismiss(rawError: String) {
        if (!_sessionActive.value) return
        sttDismissJob?.cancel()
        AssistantDebugLog.d(TAG, "STT terminal error → SessionComplete shortly ($rawError)")
        sttDismissJob = scope.launch {
            delay(STT_ERROR_DISMISS_MS)
            if (!_sessionActive.value) return@launch
            // Clear before stopSession() so it does not cancel this coroutine mid-emit.
            sttDismissJob = null
            stopSession()
            emitPipelineMood(AssistantMoodId.Idle)
            _events.emit(AssistantSessionEvent.SessionComplete)
        }
    }

    private suspend fun emitPipelineMood(mood: AssistantMoodId) {
        pipelineMood = mood
        publishResolvedMood()
    }

    private suspend fun publishResolvedMood() {
        val resolved = FaceMoodResolver.resolve(pipelineMood, affectiveMood)
        _events.emit(AssistantSessionEvent.MoodChanged(resolved))
    }

    /**
     * Strip optional LLM `<mood>…</mood>` and `<face …/>` tags from assistant text,
     * apply affective mood + cues from conversation context, and show cleaned transcript
     * (tags are never spoken / shown).
     */
    private suspend fun emitAssistantText(raw: String, mouthAmplitude: Float) {
        val moodParsed = MoodTagParser.parse(raw)
        if (moodParsed.found) {
            affectiveMood = moodParsed.mood?.takeIf { FaceMoodResolver.isAffective(it) }
            publishResolvedMood()
        }

        val faceParsed = FaceCueParser.parse(moodParsed.cleanedText)
        val text = faceParsed.cleanedText.ifBlank { moodParsed.cleanedText }.ifBlank { raw }
        val cues = when {
            faceParsed.found -> faceParsed.cues?.takeUnless { it.isEmpty }
            else -> {
                // DirectTool weather / music / nav has no <face> tags — use pending / spoken infer.
                ToolFaceCues.forIconId(PendingToolFaceCues.take())
                    ?: ToolFaceCues.fromSpokenText(text)
            }
        }
        if (faceParsed.found || cues != null) {
            _events.emit(AssistantSessionEvent.FaceCuesChanged(cues))
        }
        _events.emit(
            AssistantSessionEvent.Transcript(
                text = text,
                speaker = AssistantSpeaker.Assistant,
            ),
        )
        _events.emit(AssistantSessionEvent.MouthAmplitude(mouthAmplitude))
    }

    companion object {
        private const val TAG = "VehicleAgentBackend"
        /**
         * Brief pause so wake-word AudioRecord can release before agent STT.
         * Keep tiny — Google / Gemini open the ear almost immediately on tap.
         */
        private const val MIC_OPEN_DELAY_MS = 80L
        private const val MIC_RESTART_SETTLE_MS = 250L
        private const val MIC_REBUILD_SETTLE_MS = 450L
        /** Brief beat so "I didn't catch that." is readable before dismiss. */
        private const val STT_ERROR_DISMISS_MS = 1_200L
        private const val STT_MODELS_MISSING_MSG =
            "STT models missing — push Whisper to /data/local/tmp/stt (see docs/MODEL_SIDELOAD.md)"
    }
}

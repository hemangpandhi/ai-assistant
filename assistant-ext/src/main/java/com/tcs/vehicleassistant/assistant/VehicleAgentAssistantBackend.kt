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
import com.assistant.ui.assistant.api.FaceCueParser
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
    private var pendingFinalQuery: String? = null
    /** True after onReadyForSpeech until stop / result / error. */
    private var micArmed = false
    private var clientErrorRetries = 0
    /** Latched once we know Whisper sideloads are absent — skip fruitless STT retries. */
    private var sherpaModelsMissing = false

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
        uiCollectJob = scope.launch {
            vm.uiState.collect { state -> mapUiState(state) }
        }
        eventCollectJob = scope.launch {
            vm.events.collect { event ->
                when (event) {
                    is ViewModelEvent.StartListening -> {
                        AssistantDebugLog.d(TAG, "event StartListening")
                        scheduleStartMic(reason = "orchestrator", delayMs = 350L, force = true)
                    }
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
                        scheduleStartMic(reason = "finish-retry", delayMs = 500L, force = true)
                    }
                    else -> Unit
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
        scheduleStartMic(reason = "startSession:$reason", delayMs = 1_400L, force = true)
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

    override fun requestListen() {
        if (!_sessionActive.value) {
            _sessionActive.value = true
        }
        if (listenJob?.isActive == true || micArmed) {
            AssistantDebugLog.d(TAG, "requestListen skipped (scheduled/armed)")
            return
        }
        AssistantDebugLog.d(TAG, "requestListen")
        scheduleStartMic(reason = "session-request", delayMs = 1_400L, force = true)
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
        vm.handleQuery(q)
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
            emitMood(AssistantMoodId.Sad)
            _events.emit(AssistantSessionEvent.Error(STT_MODELS_MISSING_MSG))
            return true
        }
        return try {
            // Forced starts must actually restart. A plain startListening() no-ops while
            // isListening=true and returns "success", leaving the mic unarmed.
            // Prefer stop-only: destroySpeechRecognizer() nulls Sherpa and races AudioRecord
            // release, which surfaces as error code 0 → "Unknown recognition error".
            if (force) {
                runCatching { audio.stopListening() }
                if (rebuildRecognizer) {
                    runCatching { audio.destroySpeechRecognizer() }
                }
                // Settle so prior AudioRecord / Google recognizer can release the mic.
                delay(if (rebuildRecognizer) MIC_REBUILD_SETTLE_MS else MIC_RESTART_SETTLE_MS)
            }
            audio.startListening()
            AssistantDebugLog.d(
                TAG,
                "startMic($reason) issued force=$force rebuild=$rebuildRecognizer",
            )
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
                _events.emit(AssistantSessionEvent.FaceCuesChanged(null))
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
                _events.emit(AssistantSessionEvent.FaceCuesChanged(null))
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
                AssistantDebugLog.d(TAG, "ui Streaming ${state.displayText.take(40)}")
                emitMood(AssistantMoodId.Speaking)
                emitAssistantText(state.displayText, mouthAmplitude = 0.35f)
            }
            is AssistantUiState.Speaking -> {
                micArmed = false
                AssistantDebugLog.d(TAG, "ui Speaking ${state.finalMessage.take(40)}")
                emitMood(AssistantMoodId.Speaking)
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
                val display = if (missingModels) STT_MODELS_MISSING_MSG else raw
                AssistantDebugLog.e(TAG, "ui Error: $display (raw=$raw)")
                emitMood(AssistantMoodId.Sad)
                _events.emit(AssistantSessionEvent.Error(display))
                _events.emit(AssistantSessionEvent.MouthAmplitude(null))

                // Do not retry when Whisper sideloads are absent — same failure forever.
                if (missingModels) return

                // Recoverable STT failures: CLIENT/BUSY, AudioRecord contention, etc.
                val recoverable = msg.contains("client") ||
                    msg.contains("busy") ||
                    msg.contains("unknown recognition") ||
                    msg.contains("audio recording") ||
                    msg.contains("(5)") ||
                    msg.contains("(8)")
                if (recoverable && clientErrorRetries < 2 && _sessionActive.value) {
                    clientErrorRetries += 1
                    AssistantDebugLog.w(
                        TAG,
                        "STT retry #$clientErrorRetries after: $raw",
                    )
                    scheduleStartMic(
                        reason = "stt-retry",
                        delayMs = 350L,
                        force = true,
                        rebuildRecognizer = true,
                    )
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
        val prefs = ctx.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val engine = prefs.getString(
            AssistantConfig.Prefs.STT_ENGINE,
            AssistantConfig.Prefs.STT_ENGINE_SHERPA,
        )
        return engine != AssistantConfig.Prefs.STT_ENGINE_GOOGLE
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

    private suspend fun emitMood(mood: AssistantMoodId) {
        _events.emit(AssistantSessionEvent.MoodChanged(mood))
    }

    /**
     * Strip optional LLM `<face …/>` tags from assistant text, apply cues, and
     * show the cleaned transcript (tags are never spoken / shown).
     */
    private suspend fun emitAssistantText(raw: String, mouthAmplitude: Float) {
        val parsed = FaceCueParser.parse(raw)
        if (parsed.found) {
            _events.emit(
                AssistantSessionEvent.FaceCuesChanged(
                    parsed.cues?.takeUnless { it.isEmpty },
                ),
            )
        }
        val text = parsed.cleanedText.ifBlank { raw }
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
        private const val MIC_RESTART_SETTLE_MS = 250L
        private const val MIC_REBUILD_SETTLE_MS = 450L
        private const val STT_MODELS_MISSING_MSG =
            "STT models missing — push Whisper to /data/local/tmp/stt (see docs/MODEL_SIDELOAD.md)"
    }
}

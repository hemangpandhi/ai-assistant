package com.tcs.vehicleassistant.repository

import android.content.Context
import android.content.Intent
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.tcs.vehicleassistant.*
import com.tcs.vehicleassistant.assistant.agent.ConfirmationCoordinator
import com.tcs.vehicleassistant.assistant.agent.TurnRouter
import com.tcs.vehicleassistant.CloudMessageCallback
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.hardware.CabinSnapshotReader
import com.tcs.vehicleassistant.core.VisionState
import com.tcs.vehicleassistant.core.ConfirmationPolicy
import com.tcs.vehicleassistant.core.ContextGuard
import com.tcs.vehicleassistant.core.DirectToolResolver
import com.tcs.vehicleassistant.llm.ILLMProvider
import com.tcs.vehicleassistant.utils.FollowUpRouter
import com.tcs.vehicleassistant.utils.EmergencyAlarmManager
import com.tcs.vehicleassistant.utils.ToolCallParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class OrchestratorState {
    object Idle : OrchestratorState()
    data class Thinking(val query: String? = null) : OrchestratorState()
    data class Streaming(val displayMsg: String) : OrchestratorState()
    data class Speaking(val finalMsg: String) : OrchestratorState()
    data class Error(val message: String) : OrchestratorState()
}

sealed class OrchestratorEvent {
    data class ShowToast(val message: String) : OrchestratorEvent()
    data class SetInputEnabled(val enabled: Boolean) : OrchestratorEvent()
    data class LaunchIntent(val intent: Intent) : OrchestratorEvent()
    object StartListening : OrchestratorEvent()
    object FinishSession : OrchestratorEvent()
}

class AgentOrchestrator(
    private val context: Context,
    private val audioManager: com.tcs.vehicleassistant.hardware.IAudioManager,
    private val toolRegistry: com.tcs.vehicleassistant.domain.tools.ToolRegistry,
    private val toolSchemaGenerator: com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator,
    private val toolExecutor: com.tcs.vehicleassistant.domain.tools.IToolExecutor
) {
    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<OrchestratorEvent> = _events.asSharedFlow()

    // MVI Architecture: Run heavy orchestrator logic on background thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isQueryProcessed = true
    private var timeoutJob: Job? = null
    /**
     * Pending ContextGuard confirm vs soft conversational offer (e.g. wellness music).
     * Owned by [ConfirmationCoordinator] so "yes" cannot hit the wrong path.
     */
    private val confirmationCoordinator = ConfirmationCoordinator()

    private val pendingIntentsToLaunch = java.util.Collections.synchronizedList(mutableListOf<Intent>())

    /**
     * Monotonically increasing turn id. LiteRT streams on its own thread and keeps delivering
     * tokens after a turn is abandoned (timeout, hallucination cut-off, re-triggered session), so
     * every callback checks its captured id against this before touching any state.
     */
    private val turnCounter = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var activeTurnId = 0L

    @Volatile var ttsSpokenLength = 0
        private set
    @Volatile var lastTtsUpdateTime = 0L
        private set

    private val currentPendingTools = java.util.Collections.synchronizedList(mutableListOf<Deferred<String?>>())

    /**
     * Per-turn streaming state. LiteRT invokes `onMessage` from a native thread while `onDone`
     * work runs on the main dispatcher, so every field here is mutated under [lock] rather than
     * from shared orchestrator fields as it was previously.
     */
    private class StreamState(val turnId: Long) {
        private val lock = Any()
        private val response = StringBuilder()

        var isHallucinating = false
            private set
        var spokenLength = 0
            private set
        var parsedLength = 0
            private set

        @Volatile
        var isDoneCalled = false

        fun append(text: String): String = synchronized(lock) {
            response.append(text)
            response.toString()
        }

        fun replace(text: String): Unit = synchronized(lock) {
            response.setLength(0)
            response.append(text)
        }

        fun snapshot(): String = synchronized(lock) { response.toString() }

        fun clear(): Unit = synchronized(lock) { response.setLength(0) }

        fun markHallucinating() = synchronized(lock) { isHallucinating = true }

        /** Advances the TTS bookkeeping for one dispatched sentence, returning its start offset. */
        fun consumeSentence(length: Int): Int = synchronized(lock) {
            val startOffset = parsedLength
            spokenLength += length
            parsedLength += length
            startOffset
        }

        fun markSpoken(length: Int) = synchronized(lock) { spokenLength += length }
    }

    init {
        audioManager.setUtteranceListener(
            onStart = { utteranceId ->
                if (utteranceId.startsWith("SENTENCE_")) {
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
            },
            onDone = { utteranceId ->
                if (utteranceId.startsWith("SENTENCE_")) {
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
                onTtsFinalUtteranceDone(utteranceId)
            },
            onError = { utteranceId ->
                onTtsFinalUtteranceError(utteranceId)
            },
            onRangeStart = { utteranceId, start, end, frame ->
                if (utteranceId.startsWith("SENTENCE_")) {
                    val sentenceStartOffset = utteranceId.substringAfter("SENTENCE_").toIntOrNull() ?: 0
                    ttsSpokenLength = Math.max(ttsSpokenLength, sentenceStartOffset + end)
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
            }
        )
    }

    fun isProcessing(): Boolean = !isQueryProcessed

    fun triggerProactiveEvent(prompt: String) {
        handleQuery(prompt)
    }

    private var currentSpeakerName = VisionState.recognizedUser.takeIf { it != "Guest" } ?: "User"

    fun handleConfirmation(accepted: Boolean) {
        val query = if (accepted) "yes" else "no"
        handleQuery(query)
    }

    fun handleQuery(query: String, retryCount: Int = 0) {
        val normalized = TurnRouter.normalize(
            rawQuery = query,
            defaultSpeaker = currentSpeakerName,
        )
        if (normalized.speakerName != null) {
            currentSpeakerName = normalized.speakerName
        }
        val trimmedQuery = normalized.query
        if (normalized.collapsedFromAsrRepeat) {
            android.util.Log.i(TAG, "Collapsed ASR repeat: '$query' → '$trimmedQuery'")
        }

        // Keep mutable pending state aligned with TurnRouter's "OTHER supersedes" rules.
        for (label in confirmationCoordinator.applySupersedeIfNeeded(trimmedQuery)) {
            LatencyLogger.log("Orchestrator", "Pending $label superseded by: $trimmedQuery")
        }

        val pendingSnap = confirmationCoordinator.snapshot()
        val directHit = resolveDirectHitOrNull(trimmedQuery)?.let {
            TurnRouter.DirectHit(
                toolCall = it.toolCall,
                spokenResponse = it.spokenResponse,
                matchedKeyword = it.matchedKeyword,
                reason = it.reason,
            )
        }
        val followUpTool = if (pendingSnap.pendingConfirmationTool == null) {
            FollowUpRouter.resolveDirectTool(trimmedQuery, LLMManager.lastAiResponse)
        } else {
            null
        }

        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = trimmedQuery,
                retryCount = retryCount,
                pendingConfirmationTool = pendingSnap.pendingConfirmationTool,
                pendingOfferedTool = pendingSnap.pendingOfferedTool,
                isAffirmativeKeepAlive = MemoryManager.isAffirmative(trimmedQuery),
                directHit = directHit,
                followUpToolCall = followUpTool,
                modelReady = LLMManager.isReady(),
                cloudModelActive = LocalLLMActivity.isCloudModelActive,
            ),
        )
        executeTurnDecision(decision)
    }

    private fun executeTurnDecision(decision: TurnRouter.Decision) {
        when (decision) {
            is TurnRouter.Decision.ContextGuardDecline -> {
                confirmationCoordinator.clearAll()
                LatencyLogger.reset()
                beginTurn()
                scope.launch {
                    finishGuardedTurn(
                        message = decision.message,
                        pathLabel = "ContextGuardConfirm",
                        toolCall = "cancelled",
                        policyId = "user_declined",
                        asQuestion = false,
                    )
                }
            }
            is TurnRouter.Decision.ContextGuardAffirm -> {
                confirmationCoordinator.clearAll()
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                beginTurn()
                _state.value = OrchestratorState.Thinking(decision.query)
                _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
                scope.launch {
                    completeDirectToolTurn(
                        query = decision.query,
                        toolCall = decision.toolCall,
                        preferredSpoken = null,
                        pathLabel = "ContextGuardConfirm",
                        skipGuard = true,
                    )
                }
            }
            is TurnRouter.Decision.OfferDecline -> {
                confirmationCoordinator.clearOffer()
                LatencyLogger.reset()
                beginTurn()
                scope.launch {
                    finishGuardedTurn(
                        message = decision.message,
                        pathLabel = "OfferDecline",
                        toolCall = decision.declinedToolCall ?: "declined",
                        policyId = "user_declined_offer",
                        asQuestion = false,
                    )
                }
            }
            is TurnRouter.Decision.OfferAffirm -> {
                confirmationCoordinator.clearOffer()
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Offer affirmed → ${decision.toolCall}")
                beginTurn()
                _state.value = OrchestratorState.Thinking(decision.query)
                _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
                scope.launch {
                    completeDirectToolTurn(
                        query = decision.query,
                        toolCall = decision.toolCall,
                        preferredSpoken = decision.preferredSpoken,
                        pathLabel = "OfferConfirm",
                        skipGuard = false,
                    )
                }
            }
            is TurnRouter.Decision.Greeting -> {
                scope.launch {
                    finishGuardedTurn(
                        message = decision.message,
                        pathLabel = "Greeting",
                        toolCall = "",
                        policyId = "greeting",
                        asQuestion = true,
                    )
                }
            }
            is TurnRouter.Decision.DismissSession -> {
                _events.tryEmit(OrchestratorEvent.FinishSession)
                resetState()
            }
            is TurnRouter.Decision.CrisisSupport -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                dispatchCrisisSupport(decision)
            }
            is TurnRouter.Decision.WellnessOffer -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                dispatchWellnessOffer(decision.query)
            }
            is TurnRouter.Decision.DirectTool -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                beginTurn()
                _state.value = OrchestratorState.Thinking(decision.query)
                _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
                LatencyLogger.log(
                    "DirectTool",
                    "Matched ${decision.toolCall} via '${decision.matchedKeyword}' (${decision.reason})",
                )
                scope.launch {
                    completeDirectToolTurn(
                        query = decision.query,
                        toolCall = decision.toolCall,
                        preferredSpoken = decision.preferredSpoken,
                        pathLabel = "DirectTool",
                    )
                }
            }
            is TurnRouter.Decision.FollowUp -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                dispatchFollowUp(decision.query, decision.toolCall)
            }
            is TurnRouter.Decision.EnsureModelThenRetry -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                _state.value = OrchestratorState.Thinking(decision.query)
                _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
                scope.launch {
                    try {
                        val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin()
                            .inject(org.koin.core.qualifier.named("edge"))
                        edgeProvider.initialize(context, force = false)
                        handleQuery(decision.query, decision.retryCount)
                    } catch (e: Exception) {
                        _state.value = OrchestratorState.Error("Model not loaded. Open the app to load a model.")
                        _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                    }
                }
            }
            is TurnRouter.Decision.RunLlm -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                val turnId = beginTurn()
                _state.value = OrchestratorState.Thinking(decision.query)
                _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
                scope.launch {
                    processQuery(decision.query, decision.retryCount, turnId = turnId)
                }
            }
        }
    }

    private fun resolveDirectHitOrNull(query: String): com.tcs.vehicleassistant.core.DirectToolResolver.Hit? {
        val toolRegistry = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.tcs.vehicleassistant.domain.tools.ToolRegistry>()
        } catch (e: Exception) {
            return null
        }
        if (!toolRegistry.isInitialized) {
            toolRegistry.initialize(context.applicationContext)
        }
        return toolRegistry.resolveDirectHit(query)
    }

    /**
     * Opens a new turn and invalidates any in-flight one, so late callbacks from an abandoned
     * inference are dropped rather than overwriting the new turn's UI and TTS state.
     */
    private fun beginTurn(): Long {
        val turnId = turnCounter.incrementAndGet()
        activeTurnId = turnId
        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        isQueryProcessed = false
        return turnId
    }

    private fun isCurrentTurn(turnId: Long): Boolean = activeTurnId == turnId

    fun resetState() {
        _state.value = OrchestratorState.Idle
    }

    private fun onTtsFinalUtteranceDone(utteranceId: String) {
        scope.launch {
            when (utteranceId) {
                "QUESTION_FINAL" -> {
                    delay(500)
                    _events.tryEmit(OrchestratorEvent.StartListening)
                }
                "STATEMENT_FINAL_TOOL" -> {
                    for (job in currentPendingTools) {
                        try { job.await() } catch (_: Exception) {}
                    }
                    for (intent in pendingIntentsToLaunch) {
                        _events.tryEmit(OrchestratorEvent.LaunchIntent(intent))
                    }
                    pendingIntentsToLaunch.clear()
                    delay(50)
                    // Soft offer still waiting for yes/no — keep overlay up and listen.
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(OrchestratorEvent.StartListening)
                    } else {
                        _events.tryEmit(OrchestratorEvent.FinishSession)
                    }
                }
                "STATEMENT_FINAL" -> {
                    delay(50)
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(OrchestratorEvent.StartListening)
                    } else {
                        _events.tryEmit(OrchestratorEvent.FinishSession)
                    }
                }
            }
        }
    }

    private fun onTtsFinalUtteranceError(utteranceId: String) {
        scope.launch {
            when (utteranceId) {
                "QUESTION_FINAL" -> {
                    delay(500)
                    _events.tryEmit(OrchestratorEvent.StartListening)
                }
                "STATEMENT_FINAL_TOOL" -> {
                    for (job in currentPendingTools) {
                        try { job.await() } catch (_: Exception) {}
                    }
                    for (intent in pendingIntentsToLaunch) {
                        _events.tryEmit(OrchestratorEvent.LaunchIntent(intent))
                    }
                    pendingIntentsToLaunch.clear()
                    delay(3000) // Artificial delay to allow user to read text since TTS failed
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(OrchestratorEvent.StartListening)
                    } else {
                        _events.tryEmit(OrchestratorEvent.FinishSession)
                    }
                }
                "STATEMENT_FINAL" -> {
                    delay(4000) // Artificial delay to allow user to read text since TTS failed
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(OrchestratorEvent.StartListening)
                    } else {
                        _events.tryEmit(OrchestratorEvent.FinishSession)
                    }
                }
            }
        }
    }

    fun destroy() {
        timeoutJob?.cancel()
        scope.cancel()
        EmergencyAlarmManager.stop()
    }

    private fun dispatchCrisisSupport(decision: TurnRouter.Decision.CrisisSupport) {
        beginTurn()
        _state.value = OrchestratorState.Thinking(decision.query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
        LatencyLogger.log(
            "CrisisSupport",
            "Matched ${decision.severityName} utterance — entertainment offers suppressed",
        )
        scope.launch {
            MemoryManager.captureLongTermFacts(context, decision.query)
            MemoryManager.addTurn(currentSpeakerName, decision.query)
            confirmationCoordinator.clearOffer()
            finishGuardedTurn(
                message = decision.spokenResponse,
                pathLabel = "CrisisSupport",
                toolCall = "crisis_support",
                policyId = "conversation_safety_${decision.severityName.lowercase()}",
                asQuestion = true,
            )
        }
    }

    private fun dispatchWellnessOffer(query: String) {
        beginTurn()
        _state.value = OrchestratorState.Thinking(query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
        LatencyLogger.log("WellnessOffer", "Matched emotional/open-ended wellness utterance")
        scope.launch {
            MemoryManager.captureLongTermFacts(context, query)
            MemoryManager.addTurn(currentSpeakerName, query)
            confirmationCoordinator.setSoftOffer("playMusic(relaxing)")
            finishGuardedTurn(
                message = WELLNESS_OFFER,
                pathLabel = "WellnessOffer",
                toolCall = "wellness_offer",
                policyId = null,
                asQuestion = true,
            )
        }
    }

    private fun dispatchFollowUp(query: String, toolCall: String) {
        confirmationCoordinator.clearOffer()
        beginTurn()
        _state.value = OrchestratorState.Thinking(query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
        LatencyLogger.log("FollowUp", "Matched $toolCall")

        scope.launch {
            if (toolCall.startsWith("handleDrowsyDriving") || FollowUpRouter.isDrowsyDriverQuery(query)) {
                EmergencyAlarmManager.start(context)
            }

            val preferred = when {
                toolCall.startsWith("handleDrowsyDriving") ->
                    "Hey — stay with me! I'm cooling the cabin and cranking upbeat music to help you stay alert."
                toolCall.startsWith("stopMusic") -> "No problem, I've stopped the music for you right away."
                toolCall.startsWith("playMusic") -> {
                    val song = toolCall.substringAfter("(").substringBefore(")").trim()
                        .removeSurrounding("\"")
                    if (song.isNotBlank() && !song.equals("music", ignoreCase = true)) {
                        "Great choice — putting on $song for you!"
                    } else {
                        "Sure — playing something calming for you."
                    }
                }
                toolCall.startsWith("increaseTemperature") -> "I'm warming up the cabin for you right away!"
                toolCall.startsWith("decreaseTemperature") -> "I'm cooling down the cabin for you right away!"
                else -> null
            }

            completeDirectToolTurn(
                query = query,
                toolCall = toolCall,
                preferredSpoken = preferred,
                pathLabel = "FollowUp",
            )
        }
    }

    /**
     * Shared completion for DirectTool / FollowUp: ContextGuard → execute (or confirm/block),
     * surface a short reply, speak it, and log whether the E2E budget was met.
     *
     * @param skipGuard when true (user already confirmed a ContextGuard prompt), do not ask Confirm
     * again. Hard [ContextGuard.Decision.Block] is still honored for safety.
     */
    private suspend fun completeDirectToolTurn(
        query: String,
        toolCall: String,
        preferredSpoken: String?,
        pathLabel: String,
        skipGuard: Boolean = false,
    ) {
        MemoryManager.captureLongTermFacts(context, query)
        MemoryManager.addTurn(currentSpeakerName, query)

        when (val decision = evaluateContextGuard(toolCall)) {
            is ContextGuard.Decision.Confirm -> {
                if (skipGuard) {
                    // Already confirmed — fall through to execute.
                } else {
                    confirmationCoordinator.setConfirmation(decision.originalToolCall)
                    finishGuardedTurn(
                        message = decision.message,
                        pathLabel = pathLabel,
                        toolCall = toolCall,
                        policyId = decision.policyId,
                        asQuestion = true,
                    )
                    return
                }
            }
            is ContextGuard.Decision.Block -> {
                finishGuardedTurn(
                    message = decision.message,
                    pathLabel = pathLabel,
                    toolCall = toolCall,
                    policyId = decision.policyId,
                    asQuestion = false,
                )
                return
            }
            is ContextGuard.Decision.Escalate -> {
                if (skipGuard) {
                    // Already confirmed — treat as allow this turn.
                } else {
                    finishGuardedTurn(
                        message = decision.message,
                        pathLabel = pathLabel,
                        toolCall = toolCall,
                        policyId = decision.policyId,
                        asQuestion = true,
                    )
                    return
                }
            }
            is ContextGuard.Decision.Allow -> Unit
        }

        val feedback = executeToolCall(toolCall) ?: "Action completed."
        // Prefer live handler feedback (destinations, setpoints, artist names) over static
        // registry success_message / DirectTool "Done." placeholders.
        val finalMsg = when {
            feedback.isNotBlank() &&
                !feedback.startsWith("System Error", ignoreCase = true) &&
                !feedback.equals("Action completed.", ignoreCase = true) -> feedback
            !preferredSpoken.isNullOrBlank() &&
                !preferredSpoken.equals("Done.", ignoreCase = true) -> preferredSpoken
            feedback.isNotBlank() -> feedback
            !preferredSpoken.isNullOrBlank() -> preferredSpoken
            else -> "Okay."
        }

        MemoryManager.addTurn("Assistant", finalMsg)
        LLMManager.lastAiResponse = finalMsg
        _state.value = OrchestratorState.Speaking(finalMsg)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
        isQueryProcessed = true

        val elapsed = LatencyLogger.getTotalTime()
        val budget = AssistantConfig.Session.END_TO_END_BUDGET_MS
        val met = elapsed in 0..budget
        LatencyLogger.log(
            pathLabel,
            "E2E ${elapsed}ms (budget ${budget}ms, ${if (met) "MET" else "MISS"}) tool=$toolCall",
        )

        if (finalMsg.isNotBlank()) {
            audioManager.speak(finalMsg, "SENTENCE_0")
            val isQuestion = finalMsg.trim().endsWith("?") ||
                finalMsg.contains("which one", ignoreCase = true)
            val finalUtterance = if (isQuestion) "QUESTION_FINAL" else "STATEMENT_FINAL_TOOL"
            audioManager.playSilentUtterance(10, finalUtterance)
        }
    }

    private suspend fun finishGuardedTurn(
        message: String,
        pathLabel: String,
        toolCall: String,
        policyId: String?,
        asQuestion: Boolean,
    ) {
        MemoryManager.addTurn("Assistant", message)
        LLMManager.lastAiResponse = message
        _state.value = OrchestratorState.Speaking(message)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
        isQueryProcessed = true
        LatencyLogger.log(
            pathLabel,
            "ContextGuard policy=$policyId tool=$toolCall msg=$message",
        )
        if (message.isNotBlank()) {
            audioManager.speak(message, "SENTENCE_0")
            audioManager.playSilentUtterance(
                10,
                if (asQuestion) "QUESTION_FINAL" else "STATEMENT_FINAL_TOOL",
            )
        }
    }

    private fun evaluateContextGuard(toolCall: String): ContextGuard.Decision {
        return try {
            val snapshot = CabinSnapshotReader.capture(context.applicationContext)
            ContextGuard.evaluate(toolCall, snapshot)
        } catch (e: Exception) {
            if (com.tcs.vehicleassistant.core.SafetyCriticalTools.isSafetyCritical(toolCall)) {
                android.util.Log.w(
                    TAG,
                    "ContextGuard snapshot failed; fail-closed for safety tool $toolCall",
                    e,
                )
                ContextGuard.Decision.Block(
                    message = com.tcs.vehicleassistant.core.SafetyCriticalTools.SNAPSHOT_UNAVAILABLE_MESSAGE,
                    policyId = com.tcs.vehicleassistant.core.SafetyCriticalTools.SNAPSHOT_UNAVAILABLE_POLICY_ID,
                )
            } else {
                android.util.Log.w(TAG, "ContextGuard snapshot failed; allowing $toolCall", e)
                ContextGuard.Decision.Allow()
            }
        }
    }

    private suspend fun processQuery(
        query: String,
        retryCount: Int = 0,
        loopCount: Int = 0,
        isAgenticObservation: Boolean = false,
        previousExecutedTools: Set<String> = emptySet(),
        turnId: Long,
        emptyChatRetry: Int = 0,
    ) {
        if (!isCurrentTurn(turnId)) return
        
        // Wait for any background native inference to drain before touching the engine state.
        // Preemptively closing the conversation while LiteRT is generating tokens corrupts the GPU context.
        while (com.tcs.vehicleassistant.LLMManager.hasActiveInference()) {
            if (!isCurrentTurn(turnId)) return
            kotlinx.coroutines.delay(100)
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            val timeoutDuration = if (LLMManager.isFirstMessage) {
                AssistantConfig.Llm.FIRST_INFERENCE_TIMEOUT_MS
            } else {
                AssistantConfig.Llm.INFERENCE_TIMEOUT_MS
            }
            delay(timeoutDuration)
            if (!isQueryProcessed && isCurrentTurn(turnId)) {
                _state.value = OrchestratorState.Error("Timeout - Restarting Model...")
                _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
                // Drain the hung stream before tearing the engine down; force-close mid-generation
                // corrupts the OpenCL/GPU context, but we must recover if permanently hung.
                if (!LLMManager.awaitInferenceDrain()) {
                    android.util.Log.e(TAG, "Model still busy after timeout. Forcing restart.")
                }
                LLMManager.autoInitialize(context, force = true, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        _state.value = OrchestratorState.Idle
                        _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                        isQueryProcessed = true
                    }
                    override fun onError(e: Exception) {
                        _state.value = OrchestratorState.Error("Error restarting.")
                        _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                        isQueryProcessed = true
                    }
                })
            }
        }

        var interceptedQuery = query

        if (confirmationCoordinator.pendingConfirmationTool != null) {
            if (MemoryManager.isDecline(query)) {
                confirmationCoordinator.clearConfirmation()
                interceptedQuery = "System: Action aborted by user. User originally said: $query"
            } else if (MemoryManager.isAffirmative(query)) {
                // Already confirmed — execute without re-entering ContextGuard.
                val toolToExecute = confirmationCoordinator.pendingConfirmationTool!!
                confirmationCoordinator.clearConfirmation()
                val feedback = executeToolCall(toolToExecute)
                interceptedQuery = "System: Executed $toolToExecute. Result: $feedback. User originally said 'yes'."
            } else {
                confirmationCoordinator.clearConfirmation()
                interceptedQuery = "System: Action aborted by user. User originally said: $query"
            }
        }

        val maxHistoryChars = toolRegistry.slidingWindowMaxChars
        val isFollowUp = MemoryManager.isFollowUpQuery(interceptedQuery)
        val historyCap = if (isFollowUp || interceptedQuery.length < 30) maxHistoryChars else minOf(1000, maxHistoryChars)
        val priorHistory = MemoryManager.getSlidingWindowContext(historyCap)

        // Recycle native conversation BEFORE building the prompt so a post-reset turn gets the
        // full system prompt (isFirstMessage=true) instead of a compact later-turn stub.
        if (!LocalLLMActivity.isCloudModelActive) {
            val needsReset = toolSchemaGenerator.needsToolUpdate(interceptedQuery, priorHistory) || com.tcs.vehicleassistant.core.ConversationResetPolicy.shouldResetBeforePrompt(
                LLMManager.nativeTurnsSinceReset,
                AssistantConfig.Llm.CONVERSATION_RESET_TURNS,
            )
            if (needsReset) {
                withContext(Dispatchers.IO) {
                    if (LLMManager.awaitInferenceDrain() && LLMManager.resetConversation(context, interceptedQuery)) {
                        android.util.Log.i(TAG, "Native conversation reset before prompt; string memory retained.")
                    } else {
                        android.util.Log.w(TAG, "Deferred conversation reset; inference still active")
                    }
                }
            }
        }

        val finalPrompt: String = withContext(Dispatchers.IO) {
            // Empty-chat retries already recorded the user turn; avoid duplicating it in memory.
            if (emptyChatRetry == 0) {
                if (isAgenticObservation) {
                    MemoryManager.addTurn("System", interceptedQuery)
                } else {
                    MemoryManager.captureLongTermFacts(context, interceptedQuery)
                    MemoryManager.addTurn(currentSpeakerName, interceptedQuery)
                }
            }

            val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
            val needsTelemetry = !isAgenticObservation && (interceptedQuery.length >= 25 || isFollowUp)
            val dynamicState = if (needsTelemetry) {
                SmartContextInjector.getInjectedContext(interceptedQuery, context)
            } else {
                ""
            }
            val isHandoffModel = LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == true
            val vehicleState = if (dynamicState.isNotEmpty()) {
                "[Internal Vehicle Telemetry (Do NOT speak or repeat to user): $dynamicState]"
            } else ""

            val visionContext = VisionState.getVisionContextString()
            val visionStateInject = "[Vision Context (Do NOT speak or repeat to user): $visionContext]\n"

            val stateInject = (if (vehicleState.isNotEmpty() && vehicleState != LLMManager.lastVehicleState) {
                LLMManager.lastVehicleState = vehicleState
                "$vehicleState\n"
            } else "") + visionStateInject

            val isLlama = LLMManager.currentModelPath?.contains("llama", ignoreCase = true) == true && LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == false

            val historyBlock = if (priorHistory.isNotEmpty()) {
                "\n[Recent Conversation]\n$priorHistory\n"
            } else {
                ""
            }

            if (isLlama) {
                val relevantToolsList = toolSchemaGenerator.getRelevantTools(interceptedQuery, LLMManager.lastAiResponse)
                val toolsJsonArr = relevantToolsList.map { "\"${it.handlerKey}\"" }.joinToString(",", "[", "]")
                """{"user_input":"${interceptedQuery.replace("\"", "\\\"")}","available_tools":$toolsJsonArr,"vehicle_context":{},"dialog_state":{}}"""
            } else {
                // Empathy hint on the *first* emotional turn, not only after an empty EOS —
                // core-tool-free prompts still need an explicit chat-only steer for Gemma-E2B.
                val chatHint = when {
                    com.tcs.vehicleassistant.core.ConversationSafetyPolicy.isCrisis(interceptedQuery) ->
                        com.tcs.vehicleassistant.core.ConversationSafetyPolicy.CRISIS_CHAT_HINT
                    emptyChatRetry > 0 ||
                        com.tcs.vehicleassistant.core.ConversationalIntent.isEmotionalOrWellness(interceptedQuery) ->
                        "[System: Reply with warm empathy only — no tools this turn. Acknowledge the feeling. " +
                            "For mild stress or low mood you may softly offer music or climate; " +
                            "never offer entertainment after accidents, injury, or emergencies.]\n"
                    else -> ""
                }
                val formattedQuery = if (LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == true) {
                    "User: $interceptedQuery"
                } else {
                    interceptedQuery
                }

                // Tools and identity rules are query-dependent. On turn 1 they ride inside the full
                // system prompt; on later turns LiteRT only sees the bare user text unless we
                // re-inject them — which is when the model starts saying it is a text-only AI that
                // cannot play music.
                val toolsForTurn = toolSchemaGenerator.getLlmToolsPrompt(interceptedQuery, LLMManager.lastAiResponse)
                LLMManager.lastInjectedTools = toolsForTurn
                val toolsBlock = if (toolsForTurn.isNotBlank()) {
                    "=== AVAILABLE TOOLS ===\n$toolsForTurn\n"
                } else {
                    ""
                }

                if (LLMManager.isFirstMessage) {
                    LLMManager.isFirstMessage = false
                    buildString {
                        append("<start_of_turn>system\n")
                        append(sysPrompt)
                        append("\n<end_of_turn>\n<start_of_turn>user\n")
                        if (historyBlock.isNotBlank()) append(historyBlock)
                        if (stateInject.isNotBlank()) append('\n').append(stateInject)
                        if (chatHint.isNotBlank()) append('\n').append(chatHint)
                        append('\n').append(formattedQuery)
                        append("\n<end_of_turn>\n<start_of_turn>model\n")
                    }.trim()
                } else {
                    // Re-inject tools. LiteRT KV cache already contains the conversation history.
                    // DO NOT re-inject historyBlock, as it will duplicate the history and crash the context window.
                    buildString {
                        append("<start_of_turn>user\n")
                        append(LLMManager.capabilityReminder())
                        append('\n')
                        append(toolsBlock)
                        if (stateInject.isNotBlank()) append(stateInject)
                        if (chatHint.isNotBlank()) append(chatHint)
                        append(formattedQuery)
                        append("\n<end_of_turn>\n<start_of_turn>model\n")
                    }.trim()
                }
            }
        }

        val executedTools = mutableSetOf<String>()
        executedTools.addAll(previousExecutedTools)
        val toolFeedbacks = mutableListOf<String>()
        currentPendingTools.clear()

        val stream = StreamState(turnId)
        val startTime = System.currentTimeMillis()
        val firstTokenTime = java.util.concurrent.atomic.AtomicLong(-1L)

        val onToken: (String) -> Unit = { chunkText ->
            // Invoked on LiteRT's native streaming thread. Bail out for abandoned turns so a
            // stale inference cannot overwrite the UI or queue TTS for a question already gone.
            if (!stream.isHallucinating && isCurrentTurn(turnId) && !isQueryProcessed) {
                if (firstTokenTime.compareAndSet(-1L, System.currentTimeMillis())) {
                    LatencyLogger.log("Orchestrator", "TTFT: ${firstTokenTime.get() - startTime}ms")
                }

                var currentText = stream.append(chunkText)

                var stripped = true
                while (stripped) {
                    stripped = false
                    for (prefix in ROLE_PREFIXES) {
                        if (currentText.trimStart().startsWith(prefix, ignoreCase = true)) {
                            currentText = currentText.trimStart().substring(prefix.length).trimStart()
                            stripped = true
                        }
                    }
                }

                // Strip trailing, inline, or mangled chat-template tokens. Tool tags are preserved
                // here because onDone still needs them to extract tool calls.
                currentText = currentText
                    .replace(SPECIAL_TOKEN_REGEX, "")
                    .replace(ROLE_TOKEN_REGEX, "")
                stream.replace(currentText)

                val userIdx = currentText.indexOf("\nUser:")
                if (userIdx != -1) {
                    stream.markHallucinating()
                    currentText = currentText.substring(0, userIdx)
                    stream.replace(currentText)
                } else if (currentText.trim().endsWith("User:")) {
                    stream.markHallucinating()
                    currentText = currentText.substringBeforeLast("User:")
                    stream.replace(currentText)
                }

                if (currentText.length > AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH) {
                    if (isRunawayGeneration(currentText)) {
                        stream.markHallucinating()
                    }
                }

                val displayMsg = normalizeForDisplay(ToolCallParser.stripToolTags(currentText))

                if (displayMsg.isNotEmpty()) {
                    _state.value = OrchestratorState.Streaming(displayMsg)
                }

                var remainingText = displayMsg.substring(Math.min(stream.spokenLength, displayMsg.length))
                var match = SENTENCE_REGEX.find(remainingText)
                while (match != null) {
                    val sentence = match.value
                    val sentenceStartOffset = stream.consumeSentence(sentence.length)
                    audioManager.speak(sentence, "SENTENCE_$sentenceStartOffset")

                    val nextStart = Math.min(stream.spokenLength, displayMsg.length)
                    remainingText = displayMsg.substring(nextStart)
                    match = SENTENCE_REGEX.find(remainingText)
                }
            }
        }

        val onDone: (String, List<com.tcs.vehicleassistant.utils.ParsedToolCall>) -> Unit = { _, nativeToolCalls ->
            if (!stream.isDoneCalled && isCurrentTurn(turnId)) {
                stream.isDoneCalled = true

                val tempFinalMsg = stream.snapshot()

                scope.launch {
                    timeoutJob?.cancel()

                    if (FollowUpRouter.responseRequestsAlarm(tempFinalMsg)) {
                        EmergencyAlarmManager.start(context)
                    }

                    MemoryManager.addTurn("Assistant", tempFinalMsg.trim())

                    var finalMsg = normalizeForDisplay(ToolCallParser.stripToolTags(tempFinalMsg))
                    finalMsg = com.tcs.vehicleassistant.core.ConversationSafetyPolicy
                        .sanitizeAssistantReply(query, finalMsg)
                    android.util.Log.i(
                        TAG,
                        "Model done. raw='${tempFinalMsg.take(160)}' display='${finalMsg.take(160)}' query='${query.take(80)}'",
                    )

                    val isQuestion = finalMsg.trim().endsWith("?") ||
                        finalMsg.contains("would you like", ignoreCase = true) ||
                        finalMsg.contains("if you'd like", ignoreCase = true) ||
                        finalMsg.contains("do you want", ignoreCase = true) ||
                        finalMsg.contains("shall i", ignoreCase = true)

                    LLMManager.lastAiResponse = finalMsg
                    // Soft music offer with no tool tags yet — stash for bare "yes".
                    // Never stash entertainment after a crisis user turn.
                    if (
                        isQuestion &&
                        FollowUpRouter.offeredMusic(finalMsg) &&
                        !com.tcs.vehicleassistant.core.ConversationSafetyPolicy.forbidsEntertainmentOffer(query)
                    ) {
                        confirmationCoordinator.setSoftOffer("playMusic(relaxing)")
                        android.util.Log.i(TAG, "Stashed soft music offer for follow-up affirm")
                    } else if (
                        com.tcs.vehicleassistant.core.ConversationSafetyPolicy.forbidsEntertainmentOffer(query)
                    ) {
                        confirmationCoordinator.clearOffer()
                    }
                    
                    // Don't emit empty finalMsg to avoid clearing the UI
                    val displayFinalMsg = if (finalMsg.isBlank()) TAKING_ACTION_PLACEHOLDER else finalMsg
                    _state.value = OrchestratorState.Speaking(displayFinalMsg)

                    // Keep input disabled until tools (and any agentic follow-up) finish. Marking the
                    // turn complete here used to let a new query clear currentPendingTools mid-flight.

                    // Flush any remaining text to TTS FIRST before extracting tools
                    if (finalMsg.isNotBlank()) {
                        val safeIndex = Math.min(stream.spokenLength, finalMsg.length)
                        val remainingSentence = finalMsg.substring(safeIndex).trim()
                        if (remainingSentence.isNotEmpty()) {
                            val sentenceStartOffset = stream.consumeSentence(remainingSentence.length)
                            audioManager.speak(remainingSentence, "SENTENCE_$sentenceStartOffset")
                        }
                    }

                    // Now parse tools — use a turn-local list so a newer turn cannot clear ours.
                    val pendingTools = mutableListOf<Deferred<String?>>()
                    val confirmationAsks = mutableListOf<String>()
                    val actuallyExecutedTools = linkedSetOf<String>()
                    
                    // Combine native tool calls with any text-parsed tool calls (for Cloud fallback)
                    val parsedTools = nativeToolCalls + ToolCallParser.extractToolCalls(tempFinalMsg)
                    for (parsed in parsedTools) {
                        val toolCall = "${parsed.toolName}(${parsed.args})"
                        if (executedTools.add(toolCall)) {
                            if (
                                com.tcs.vehicleassistant.core.ConversationSafetyPolicy
                                    .forbidsEntertainmentOffer(query) &&
                                com.tcs.vehicleassistant.core.ConversationSafetyPolicy
                                    .isEntertainmentTool(toolCall)
                            ) {
                                android.util.Log.w(TAG, "Crisis turn blocked entertainment tool: $toolCall")
                                toolFeedbacks.add(
                                    com.tcs.vehicleassistant.core.ConversationSafetyPolicy
                                        .evaluate(query).spokenResponse,
                                )
                                continue
                            }
                            if (!toolSchemaGenerator.isToolAllowedForCurrentPrompt(parsed.toolName)) {
                                android.util.Log.w(TAG, "LLM tool rejected by allow-list: $toolCall")
                                toolFeedbacks.add(
                                    com.tcs.vehicleassistant.core.LlmToolAllowList.rejectionMessage(parsed.toolName),
                                )
                                continue
                            }
                            val toolDef = toolRegistry.getToolDefinition(toolCall)
                            if (toolDef?.requiresConfirmation == true) {
                                // Stash only — never narrate as "I ran X" and never write yet.
                                confirmationCoordinator.setConfirmation(toolCall)
                                val ask = com.tcs.vehicleassistant.core.LlmToolTurnPolicy.confirmationAskMessage(
                                    parsed.toolName,
                                    toolDef.confirmationMessage,
                                )
                                confirmationAsks.add(ask)
                                toolFeedbacks.add(ask)
                                android.util.Log.i(TAG, "LLM tool requires confirmation; stashed $toolCall")
                            } else {
                                val job = scope.async(Dispatchers.IO) {
                                    withTimeoutOrNull(AssistantConfig.Llm.TOOL_TIMEOUT_MS) {
                                        when (val decision = evaluateContextGuard(toolCall)) {
                                            is ContextGuard.Decision.Confirm -> {
                                                confirmationCoordinator.setConfirmation(decision.originalToolCall)
                                                confirmationAsks.add(decision.message)
                                                decision.message
                                            }
                                            is ContextGuard.Decision.Block -> decision.message
                                            is ContextGuard.Decision.Escalate -> decision.message
                                            is ContextGuard.Decision.Allow -> {
                                                val result = executeToolCall(
                                                    toolCall,
                                                    enforcePromptAllowList = true,
                                                )
                                                actuallyExecutedTools.add(toolCall)
                                                result
                                            }
                                        }
                                    } ?: "System Error: Tool execution timed out."
                                }
                                pendingTools.add(job)
                                currentPendingTools.add(job)
                            }
                        }
                    }

                    if (pendingTools.isNotEmpty()) {
                        // Do NOT emit Thinking here, as it clears the UI screen and causes flickering.
                        val feedbacks = awaitAll(*pendingTools.toTypedArray()).filterNotNull()
                        toolFeedbacks.addAll(feedbacks)
                        // Tool already ran — don't keep a soft offer pending for a later "yes".
                        if (confirmationCoordinator.pendingConfirmationTool == null) {
                            confirmationCoordinator.clearOffer()
                        }
                    }

                    if (executedTools.any { it.startsWith("handleDrowsyDriving", ignoreCase = true) }) {
                        EmergencyAlarmManager.start(context)
                    }

                    if (pendingTools.isNotEmpty()) {
                        val isAgenticLoopEnabled = context
                            .getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                            .getBoolean(AssistantConfig.Prefs.AGENTIC_LOOP, true)

                        val rawResponse = stream.snapshot()
                        val responseWithoutTags = ToolCallParser.stripToolTags(rawResponse)
                        val hasConversationalText = responseWithoutTags.length > 5
                        val hasError = toolFeedbacks.any { it.contains("Error", true) || it.contains("Failed", true) || it.contains("couldn't", true) }

                        val isQueryTool = executedTools.any {
                            it.contains("search", ignoreCase = true) || it.contains("check", ignoreCase = true) ||
                            it.contains("get", ignoreCase = true) || it.contains("diagnos", ignoreCase = true) ||
                            it.contains("read", ignoreCase = true) || it.contains("find", ignoreCase = true) ||
                            it.contains("recommend", ignoreCase = true)
                        }
                        val isTerminalTool = executedTools.isNotEmpty() && !isQueryTool
                        val requiresAgenticLoop = executedTools.any { toolRegistry.getToolDefinition(it)?.requiresAgenticLoop == true }
                        val shouldRunAgenticLoop = isAgenticLoopEnabled &&
                            loopCount < AssistantConfig.Llm.MAX_AGENTIC_LOOPS &&
                            (hasError || isQueryTool || requiresAgenticLoop || (!hasConversationalText && !isTerminalTool))

                        if (shouldRunAgenticLoop) {
                            val feedbackString = toolFeedbacks.joinToString("\n")
                            val observation = "System Observation: Tool execution resulted in:\n$feedbackString\nIf the user's request is fully satisfied, respond to the user naturally. If you need to take another action based on this information, output another <TOOL> call."

                            currentPendingTools.removeAll(pendingTools.toSet())
                            stream.clear()
                            processQuery(
                                observation,
                                retryCount,
                                loopCount + 1,
                                isAgenticObservation = true,
                                previousExecutedTools = executedTools,
                                turnId = turnId
                            )
                            return@launch
                        }
                    }

                    // Prefer live tool feedback / confirmation asks over a fake "Done" or "I ran X" ACK.
                    // Confirmation-only tool tags must never narrate as if the write already happened.
                    var finalDisplayMsg = finalMsg
                    if (finalMsg.isEmpty() || finalMsg == TAKING_ACTION_PLACEHOLDER) {
                        if (confirmationAsks.isEmpty() &&
                            toolFeedbacks.isEmpty() &&
                            actuallyExecutedTools.isEmpty()
                        ) {
                            android.util.Log.w(
                                TAG,
                                "Empty model text (no tools). raw='${tempFinalMsg.take(160)}' query='${query.take(80)}'",
                            )
                            if (!LocalLLMActivity.isCloudModelActive) {
                                withContext(Dispatchers.IO) {
                                    if (LLMManager.awaitInferenceDrain() &&
                                        LLMManager.resetConversation(context, query)
                                    ) {
                                        android.util.Log.i(TAG, "Reset conversation after empty model reply")
                                    }
                                }
                            }
                            if (emptyChatRetry < 1 &&
                                com.tcs.vehicleassistant.core.ConversationalIntent.isOpenChat(query)
                            ) {
                                android.util.Log.i(TAG, "Retrying empty open-chat turn with empathy hint")
                                processQuery(
                                    query,
                                    retryCount,
                                    loopCount,
                                    isAgenticObservation,
                                    previousExecutedTools,
                                    turnId = turnId,
                                    emptyChatRetry = emptyChatRetry + 1,
                                )
                                return@launch
                            }
                        }
                        val emptyFallback = resolveEmptyModelFallback(query).also { fallback ->
                            if (fallback == WELLNESS_OFFER || FollowUpRouter.offeredMusic(fallback)) {
                                confirmationCoordinator.setSoftOffer("playMusic(relaxing)")
                                android.util.Log.i(TAG, "Stashed soft music offer from empty fallback")
                            }
                        }
                        val resolved = com.tcs.vehicleassistant.core.LlmToolTurnPolicy.resolveEmptyProseDisplay(
                            confirmationAsks = confirmationAsks,
                            toolFeedbacks = toolFeedbacks,
                            actuallyExecutedToolCalls = actuallyExecutedTools,
                            emptyFallback = emptyFallback,
                        )
                        finalDisplayMsg = resolved.text
                        if (finalDisplayMsg.isNotBlank()) {
                            audioManager.speak(finalDisplayMsg, "SENTENCE_FINAL_FB")
                        }
                    } else if (
                        com.tcs.vehicleassistant.core.LlmToolTurnPolicy.shouldSpeakToolFeedback(
                            pendingConfirmation = confirmationCoordinator.pendingConfirmationTool != null,
                            confirmationAsks = confirmationAsks,
                            toolFeedbacks = toolFeedbacks,
                        )
                    ) {
                        val feedbackMsg = when {
                            confirmationAsks.isNotEmpty() -> confirmationAsks.first()
                            else -> toolFeedbacks.joinToString(" ")
                        }
                        if (feedbackMsg.isNotBlank()) {
                            finalDisplayMsg = if (
                                finalDisplayMsg.contains(feedbackMsg, ignoreCase = true)
                            ) {
                                finalDisplayMsg
                            } else {
                                "$finalDisplayMsg $feedbackMsg".trim()
                            }
                            audioManager.speak(feedbackMsg, "SENTENCE_FINAL_CONFIRM")
                        }
                    }

                    // Update UI with the final resulting message (keep prior text on silent ignore)
                    if (finalDisplayMsg.isNotBlank()) {
                        _state.value = OrchestratorState.Speaking(finalDisplayMsg)
                    }

                    val spokenIsQuestion = finalDisplayMsg.isNotBlank() && (
                        isQuestion ||
                        confirmationCoordinator.pendingConfirmationTool != null ||
                        confirmationAsks.isNotEmpty() ||
                        com.tcs.vehicleassistant.core.LlmToolTurnPolicy.looksLikeQuestion(finalDisplayMsg) ||
                        finalDisplayMsg.contains("could you say", ignoreCase = true) ||
                        finalDisplayMsg.contains("want to try again", ignoreCase = true)
                    )
                    val finalUtterance = if (spokenIsQuestion) "QUESTION_FINAL"
                        else if (toolFeedbacks.isNotEmpty() || pendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL"
                        else "STATEMENT_FINAL"
                    audioManager.playSilentUtterance(10, finalUtterance)

                    // Only now is the turn finished — re-enable input for the next utterance.
                    // Pending confirmation must re-open the mic even if the model already spoke prose.
                    if (spokenIsQuestion && (finalMsg.isBlank() || confirmationCoordinator.pendingConfirmationTool != null)) {
                        _events.tryEmit(OrchestratorEvent.StartListening)
                    }
                    _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                    isQueryProcessed = true
                    currentPendingTools.removeAll(pendingTools.toSet())
                }
            }
        }

        val onError: (Exception) -> Unit = { throwable ->
            if (!stream.isDoneCalled && isCurrentTurn(turnId)) {
                stream.isDoneCalled = true
                scope.launch {
                    timeoutJob?.cancel()
                    if (retryCount < 1) {
                        _state.value = OrchestratorState.Error("Initializing model...")
                        try {
                            if (!LLMManager.awaitInferenceDrain()) {
                                android.util.Log.e(TAG, "Model still busy during error recovery. Forcing restart.")
                            }
                            if (LocalLLMActivity.isCloudModelActive) {
                                val cloudProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("cloud"))
                                cloudProvider.initialize(context, force = true)
                            } else {
                                val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("edge"))
                                edgeProvider.initialize(context, force = true)
                            }
                            _state.value = OrchestratorState.Thinking()
                            processQuery(
                                query,
                                retryCount + 1,
                                loopCount,
                                isAgenticObservation,
                                previousExecutedTools,
                                turnId = turnId
                            )
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Recovery re-initialization failed", e)
                            _state.value = OrchestratorState.Error("Hardware Recovery Failed.")
                            _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                            isQueryProcessed = true
                        }
                    } else {
                        if (throwable.message?.contains("Cancellation") != true) {
                            android.util.Log.e(TAG, "Inference failed after retry", throwable)
                            _state.value = OrchestratorState.Error("Model Inference Failed: ${throwable.message}")
                            _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                            isQueryProcessed = true
                        }
                    }
                }
            }
        }

        withContext(Dispatchers.IO) {
            try {
                if (LocalLLMActivity.isCloudModelActive) {
                    val cloudProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("cloud"))
                    cloudProvider.initialize(context, force = false)
                    cloudProvider.generateStream(context, finalPrompt, interceptedQuery, onToken, onDone, onError)
                } else {
                    val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("edge"))
                    edgeProvider.initialize(context, force = false)

                    // Reset already ran before prompt construction when needed.
                    LLMManager.nativeTurnsSinceReset++
                    edgeProvider.generateStream(context, finalPrompt, interceptedQuery, onToken, onDone, onError)
                }
            } catch (e: Exception) {
                if (!e.message.toString().contains("Cancellation")) {
                    onError(e)
                }
            }
        }
    }

    private suspend fun executeToolCall(
        toolCall: String,
        enforcePromptAllowList: Boolean = false,
    ): String? {
        return toolExecutor.executeToolCall(
            context.applicationContext,
            toolCall,
            enforcePromptAllowList = enforcePromptAllowList,
            intentHandler = { intent -> pendingIntentsToLaunch.add(intent) },
        )
    }

    companion object {
        private const val TAG = "AgentOrchestrator"

        private const val TAKING_ACTION_PLACEHOLDER = "Taking action..."
        private const val EMPTY_CATCH_FALLBACK = "I didn't catch that. Could you say that again?"
        private const val EMPTY_TOOL_FALLBACK = "I couldn't run a tool for that. Want to try again?"
        /** Empathy + open offer for mild wellness — not used for crisis (see ConversationSafetyPolicy). */
        internal const val WELLNESS_OFFER =
            "I'm sorry you're not feeling well. Would quiet help, or would you like me to play some music?"
        /** Last resort only after a chat-oriented LLM retry still returned empty — never "didn't catch that". */
        private const val EMPTY_CHAT_LAST_RESORT =
            "I'm here with you. Would you like me to play some music or adjust the cabin?"

        private val USER_QUESTION_PREFIX = Regex(
            "(?i)^(what|why|how|when|where|who|which|can you|could you|would you|will you|" +
                "do you|did you|is |are |am i|should |tell me|explain)\\b",
        )

        private val ACTION_REQUEST_HINT = Regex(
            "(?i)\\b(" +
                "play|stop|pause|resume|skip|next|previous|mute|unmute|volume|" +
                "turn on|turn off|increase|decrease|set|open|close|lock|unlock|" +
                "navigate|navigation|directions|call|text|message|warm|cool|" +
                "heater|defrost|ac|a\\.?c\\.?|fan|music|song|track" +
                ")\\b",
        )

        /**
         * Spoken fallback when the model produced no usable text and no tool ran.
         * Never claims "Done". "Didn't catch that" is reserved for clear questions the model
         * failed on — not for mic/TTS-echo fragments (those return blank = silent ignore).
         * Clear emotional sentences get a soft invite only as absolute last resort after retry.
         */
        internal fun resolveEmptyModelFallback(userQuery: String): String {
            val crisis = com.tcs.vehicleassistant.core.ConversationSafetyPolicy.evaluate(userQuery)
            if (crisis.isCrisis) {
                return crisis.spokenResponse
            }
            if (com.tcs.vehicleassistant.core.ConversationalIntent.isEmotionalOrWellness(userQuery)) {
                return WELLNESS_OFFER
            }
            // Bare multi-turn "yes"/"ok" with no pending offer / FollowUp match — clarify, don't dismiss.
            if (ConfirmationPolicy.isAffirmative(userQuery)) {
                return "Got it — would you like me to play some music, or something else?"
            }
            if (ConfirmationPolicy.isDecline(userQuery)) {
                return "Okay — what would you like me to do instead?"
            }
            if (com.tcs.vehicleassistant.core.ConversationalIntent.isOpenChat(userQuery)) {
                return EMPTY_CHAT_LAST_RESORT
            }
            if (looksLikeActionRequest(userQuery)) return EMPTY_TOOL_FALLBACK
            if (com.tcs.vehicleassistant.core.ConversationalIntent.isLikelyAsrGarbage(userQuery)) {
                return EMPTY_CATCH_FALLBACK
            }
            if (looksLikeUserQuestion(userQuery)) return EMPTY_CATCH_FALLBACK
            // Mid-phrase ASR echo — stay silent rather than a fake "Done" or "didn't catch that".
            return ""
        }

        /** True when the user's utterance is a question / request for information, not a cabin action. */
        internal fun looksLikeUserQuestion(userQuery: String): Boolean {
            val q = userQuery.trim()
            if (q.isEmpty() || q.startsWith("[")) return false
            if (q.endsWith("?")) return true
            return USER_QUESTION_PREFIX.containsMatchIn(q)
        }

        /** Rough cabin/media command shape — used only to pick an honest empty-model fallback. */
        internal fun looksLikeActionRequest(userQuery: String): Boolean {
            val q = userQuery.trim()
            if (q.isEmpty() || q.startsWith("[")) return false
            return ACTION_REQUEST_HINT.containsMatchIn(q)
        }

        /**
         * Chat-template role markers the model sometimes echoes back. Stripping them keeps the
         * spoken response free of transcript scaffolding.
         */
        private val ROLE_PREFIXES = listOf(
            "Assistant:", "Response:", "User:", "Assistant :", "Response :", "User :",
            "System:", "System :", "<start_of_turn>", "<end_of_turn>", "model\n", "user\n",
            "model", "user"
        )

        private val SPECIAL_TOKEN_REGEX =
            Regex("(?i)<start_of_turn>|<end_of_turn>|start_of_turn|end_of_turn|start of turn|end of turn")
        private val ROLE_TOKEN_REGEX = Regex("(?i)\\bmodel\\b\\n?|\\buser\\b\\n?")

        /** Splits streamed text into speakable sentences at terminal punctuation or newlines. */
        private val SENTENCE_REGEX =
            "^(.*?)([.!?]{2,}(?:\\s+|$)|\\n|(?<=[a-zA-Z\\)\\]\\\"])[.,!?](?:\\s+|$))".toRegex()

        private val LEADING_I_REGEX = Regex("^i\\s+")
        private val BARE_I_REGEX = Regex("^i\\b")
        private val DOUBLED_I_REGEX = Regex("\\biI\\b")
        private val I_CAN_I_REGEX = Regex("\\bi can I\\b", RegexOption.IGNORE_CASE)

        /**
         * Repairs the lowercase-`i` and doubled-pronoun artifacts this model emits mid-stream, so
         * both the on-screen text and the TTS input read as natural English.
         */
        internal fun normalizeForDisplay(text: String): String = text
            .replace(DOUBLED_I_REGEX, "I")
            .replace(I_CAN_I_REGEX, "I can")
            .replace(LEADING_I_REGEX, "")
            .replace(BARE_I_REGEX, "I")
            .trim()

        /**
         * True when generation has degenerated into a repetition loop or blown past the length
         * ceiling, both of which mean the stream should be cut off rather than spoken.
         */
        internal fun isRunawayGeneration(text: String): Boolean {
            if (text.length > AssistantConfig.Streaming.RUNAWAY_LENGTH) return true
            if (text.length <= AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH) return false
            val window = AssistantConfig.Streaming.REPETITION_WINDOW
            val lastWords = text.trim().split(Regex("\\s+")).takeLast(window)
            return lastWords.size == window && lastWords.distinct().size == 1
        }
    }
}

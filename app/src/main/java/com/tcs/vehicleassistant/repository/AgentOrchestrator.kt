package com.tcs.vehicleassistant.repository


import com.tcs.vehicleassistant.assistant.SystemPromptBuilder

import com.tcs.vehicleassistant.llm.LLMManager

import android.content.Context
import android.content.Intent
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.tcs.vehicleassistant.*

import com.tcs.vehicleassistant.manager.VoiceInteractionManager
import com.tcs.vehicleassistant.manager.VoiceInteractionCallback
import com.tcs.vehicleassistant.manager.IntentDispatcher
import com.tcs.vehicleassistant.assistant.agent.ConfirmationCoordinator
import com.tcs.vehicleassistant.assistant.agent.PromptAssembler
import com.tcs.vehicleassistant.assistant.agent.StreamTextPolicy
import com.tcs.vehicleassistant.assistant.agent.ToolLoopPlanner
import com.tcs.vehicleassistant.assistant.agent.TurnRouter
import com.tcs.vehicleassistant.assistant.agent.TurnStateMachine
import com.tcs.vehicleassistant.llm.CloudMessageCallback
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.hardware.CabinSnapshotReader
import com.tcs.vehicleassistant.core.VisionState
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

import com.tcs.vehicleassistant.assistant.agent.AgentState
import com.tcs.vehicleassistant.assistant.agent.AgentIntent
import com.tcs.vehicleassistant.assistant.agent.AgentEffect

class AgentOrchestrator(
    private val context: Context,
    private val audioManager: com.tcs.vehicleassistant.hardware.IAudioManager,
    private val toolRegistry: com.tcs.vehicleassistant.domain.tools.ToolRegistry,
    private val toolSchemaGenerator: com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator,
    private val toolExecutor: com.tcs.vehicleassistant.domain.tools.IToolExecutor,
    private val conversationMemory: com.tcs.vehicleassistant.ConversationMemory,
    private val contextGuard: com.tcs.vehicleassistant.core.ContextGuard,
    private val directToolResolver: com.tcs.vehicleassistant.core.DirectToolResolver
) {
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEffect>(extraBufferCapacity = 32)
    val events: SharedFlow<AgentEffect> = _events.asSharedFlow()

    // MVI Architecture: Run heavy orchestrator logic on background thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var timeoutJob: Job? = null
    /**
     * Pending ContextGuard confirm vs soft conversational offer (e.g. wellness music).
     * Owned by [ConfirmationCoordinator] so "yes" cannot hit the wrong path.
     */
    private val confirmationCoordinator = ConfirmationCoordinator()
    private val turnState = TurnStateMachine()


    /**
     * Monotonically increasing turn id lives on [TurnStateMachine]. LiteRT streams on its own
     * thread and keeps delivering tokens after a turn is abandoned, so every callback checks
     * [TurnStateMachine.isCurrentTurn] before touching state.
     */


    val ttsSpokenLength: Int get() = voiceManager.ttsSpokenLength
    val lastTtsUpdateTime: Long get() = voiceManager.lastTtsUpdateTime
    private val currentPendingTools = java.util.Collections.synchronizedList(mutableListOf<Deferred<String?>>())

    private val intentDispatcher = IntentDispatcher(context)
    private val voiceManager = VoiceInteractionManager(audioManager, object : VoiceInteractionCallback {
        override fun onFinalUtteranceDone(utteranceId: String) {
            this@AgentOrchestrator.onTtsFinalUtteranceDone(utteranceId)
        }
        override fun onFinalUtteranceError(utteranceId: String) {
            this@AgentOrchestrator.onTtsFinalUtteranceError(utteranceId)
        }
    })


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
    }

    fun isProcessing(): Boolean = turnState.isProcessing()

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
            directToolResolver = directToolResolver,
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
                isAffirmativeKeepAlive = com.tcs.vehicleassistant.ConversationMemory.isAffirmative(trimmedQuery),
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
                _state.value = AgentState.Thinking(decision.query)
                _events.tryEmit(AgentEffect.SetInputEnabled(false))
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
                _state.value = AgentState.Thinking(decision.query)
                _events.tryEmit(AgentEffect.SetInputEnabled(false))
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
                _events.tryEmit(AgentEffect.FinishSession)
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
                _state.value = AgentState.Thinking(decision.query)
                _events.tryEmit(AgentEffect.SetInputEnabled(false))
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
                _state.value = AgentState.Thinking(decision.query)
                _events.tryEmit(AgentEffect.SetInputEnabled(false))
                scope.launch {
                    try {
                        val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin()
                            .inject(org.koin.core.qualifier.named("edge"))
                        edgeProvider.initialize(context, force = false)
                        handleQuery(decision.query, decision.retryCount)
                    } catch (e: Exception) {
                        _state.value = AgentState.Error("Model not loaded. Open the app to load a model.")
                        _events.tryEmit(AgentEffect.SetInputEnabled(true))
                    }
                }
            }
            is TurnRouter.Decision.RunLlm -> {
                LatencyLogger.reset()
                LatencyLogger.log("Orchestrator", "Query received")
                val turnId = beginTurn()
                _state.value = AgentState.Thinking(decision.query)
                _events.tryEmit(AgentEffect.SetInputEnabled(false))
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
        val turnId = turnState.beginTurn()
        voiceManager.resetState()
        return turnId
    }

    private fun isCurrentTurn(turnId: Long): Boolean = turnState.isCurrentTurn(turnId)

    fun resetState() {
        // Invalidate stream callbacks first so they cannot re-queue speak() after stop.
        turnState.abandonTurn()
        timeoutJob?.cancel()
        timeoutJob = null
        voiceManager.stop()
        _state.value = AgentState.Idle
    }

    /** Halt TTS (Speaking or Streaming) and abandon the in-flight turn. */
    fun interruptSpeech() {
        resetState()
    }

    private fun onTtsFinalUtteranceDone(utteranceId: String) {
        scope.launch {
            if (utteranceId == "QUESTION_FINAL" || utteranceId == "STATEMENT_FINAL_TOOL" || utteranceId == "STATEMENT_FINAL") {
                for (job in currentPendingTools) {
                    try { job.await() } catch (_: Exception) {}
                }
                intentDispatcher.dispatchPendingIntents { intent ->
                    _events.tryEmit(AgentEffect.LaunchIntent(intent))
                }
            }
            when (utteranceId) {
                "QUESTION_FINAL" -> {
                    delay(500)
                    _events.tryEmit(AgentEffect.StartListening)
                }
                "STATEMENT_FINAL_TOOL" -> {
                    delay(50)
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(AgentEffect.StartListening)
                    } else {
                        _events.tryEmit(AgentEffect.FinishSession)
                    }
                }
                "STATEMENT_FINAL" -> {
                    delay(50)
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(AgentEffect.StartListening)
                    } else {
                        _events.tryEmit(AgentEffect.FinishSession)
                    }
                }
            }
        }
    }

    private fun onTtsFinalUtteranceError(utteranceId: String) {
        scope.launch {
            if (utteranceId == "QUESTION_FINAL" || utteranceId == "STATEMENT_FINAL_TOOL" || utteranceId == "STATEMENT_FINAL") {
                for (job in currentPendingTools) {
                    try { job.await() } catch (_: Exception) {}
                }
                intentDispatcher.dispatchPendingIntents { intent ->
                    _events.tryEmit(AgentEffect.LaunchIntent(intent))
                }
            }
            when (utteranceId) {
                "QUESTION_FINAL" -> {
                    delay(500)
                    _events.tryEmit(AgentEffect.StartListening)
                }
                "STATEMENT_FINAL_TOOL" -> {
                    delay(3000) // Artificial delay to allow user to read text since TTS failed
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(AgentEffect.StartListening)
                    } else {
                        _events.tryEmit(AgentEffect.FinishSession)
                    }
                }
                "STATEMENT_FINAL" -> {
                    delay(4000) // Artificial delay to allow user to read text since TTS failed
                    if (confirmationCoordinator.pendingOfferedTool != null) {
                        _events.tryEmit(AgentEffect.StartListening)
                    } else {
                        _events.tryEmit(AgentEffect.FinishSession)
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
        _state.value = AgentState.Thinking(decision.query)
        _events.tryEmit(AgentEffect.SetInputEnabled(false))
        LatencyLogger.log(
            "CrisisSupport",
            "Matched ${decision.severityName} utterance — entertainment offers suppressed",
        )
        scope.launch {
            conversationMemory.captureLongTermFacts(context, decision.query)
            conversationMemory.addTurn(currentSpeakerName, decision.query)
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
        _state.value = AgentState.Thinking(query)
        _events.tryEmit(AgentEffect.SetInputEnabled(false))
        LatencyLogger.log("WellnessOffer", "Matched emotional/open-ended wellness utterance")
        scope.launch {
            conversationMemory.captureLongTermFacts(context, query)
            conversationMemory.addTurn(currentSpeakerName, query)
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
        _state.value = AgentState.Thinking(query)
        _events.tryEmit(AgentEffect.SetInputEnabled(false))
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
        conversationMemory.captureLongTermFacts(context, query)
        conversationMemory.addTurn(currentSpeakerName, query)

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

        conversationMemory.addTurn("Assistant", finalMsg)
        LLMManager.lastAiResponse = finalMsg
        _state.value = AgentState.Speaking(finalMsg)
        _events.tryEmit(AgentEffect.SetInputEnabled(true))
        turnState.markProcessed()

        val elapsed = LatencyLogger.getTotalTime()
        val budget = AssistantConfig.Session.END_TO_END_BUDGET_MS
        val met = elapsed in 0..budget
        LatencyLogger.log(
            pathLabel,
            "E2E ${elapsed}ms (budget ${budget}ms, ${if (met) "MET" else "MISS"}) tool=$toolCall",
        )

        if (finalMsg.isNotBlank()) {
            voiceManager.speak(finalMsg, "SENTENCE_0")
            val isQuestion = finalMsg.trim().endsWith("?") ||
                finalMsg.contains("which one", ignoreCase = true)
            val finalUtterance = if (isQuestion) "QUESTION_FINAL" else "STATEMENT_FINAL_TOOL"
            voiceManager.playSilentUtterance(10, finalUtterance)
        }
    }

    private suspend fun finishGuardedTurn(
        message: String,
        pathLabel: String,
        toolCall: String,
        policyId: String?,
        asQuestion: Boolean,
    ) {
        conversationMemory.addTurn("Assistant", message)
        LLMManager.lastAiResponse = message
        _state.value = AgentState.Speaking(message)
        _events.tryEmit(AgentEffect.SetInputEnabled(true))
        turnState.markProcessed()
        LatencyLogger.log(
            pathLabel,
            "ContextGuard policy=$policyId tool=$toolCall msg=$message",
        )
        if (message.isNotBlank()) {
            voiceManager.speak(message, "SENTENCE_0")
            voiceManager.playSilentUtterance(
                10,
                if (asQuestion) "QUESTION_FINAL" else "STATEMENT_FINAL_TOOL",
            )
        }
    }

    private fun evaluateContextGuard(toolCall: String): ContextGuard.Decision {
        return try {
            val snapshot = CabinSnapshotReader.capture(context.applicationContext)
            contextGuard.evaluate(toolCall, snapshot)
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
        while (LLMManager.hasActiveInference()) {
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
            if (!turnState.isQueryProcessed && isCurrentTurn(turnId)) {
                _state.value = AgentState.Error("Timeout - Restarting Model...")
                _events.tryEmit(AgentEffect.SetInputEnabled(false))
                // Drain the hung stream before tearing the engine down; force-close mid-generation
                // corrupts the OpenCL/GPU context, but we must recover if permanently hung.
                if (!LLMManager.awaitInferenceDrain()) {
                    android.util.Log.e(TAG, "Model still busy after timeout. Forcing restart.")
                }
                LLMManager.autoInitialize(context, force = true, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        _state.value = AgentState.Idle
                        _events.tryEmit(AgentEffect.SetInputEnabled(true))
                        turnState.markProcessed()
                    }
                    override fun onError(e: Exception) {
                        _state.value = AgentState.Error("Error restarting.")
                        _events.tryEmit(AgentEffect.SetInputEnabled(true))
                        turnState.markProcessed()
                    }
                })
            }
        }

        var interceptedQuery = query

        if (confirmationCoordinator.pendingConfirmationTool != null) {
            if (com.tcs.vehicleassistant.ConversationMemory.isDecline(query)) {
                confirmationCoordinator.clearConfirmation()
                interceptedQuery = "System: Action aborted by user. User originally said: $query"
            } else if (com.tcs.vehicleassistant.ConversationMemory.isAffirmative(query)) {
                // Already confirmed — execute without re-entering contextGuard.
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
        val isFollowUp = com.tcs.vehicleassistant.ConversationMemory.isFollowUpQuery(interceptedQuery)
        val historyCap = if (isFollowUp || interceptedQuery.length < 30) maxHistoryChars else minOf(1000, maxHistoryChars)
        val priorHistory = conversationMemory.getSlidingWindowContext(historyCap)

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
                    conversationMemory.addTurn("System", interceptedQuery)
                } else {
                    conversationMemory.captureLongTermFacts(context, interceptedQuery)
                    conversationMemory.addTurn(currentSpeakerName, interceptedQuery)
                }
            }

            val sysPrompt = SystemPromptBuilder.build(context, interceptedQuery)
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
                val chatHint = PromptAssembler.chatHint(interceptedQuery, emptyChatRetry)
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
                val toolsBlock = PromptAssembler.toolsBlock(toolsForTurn)
                val first = LLMManager.isFirstMessage
                if (first) LLMManager.isFirstMessage = false
                PromptAssembler.buildGemmaTurn(
                    isFirstMessage = first,
                    sysPrompt = sysPrompt,
                    capabilityReminder = SystemPromptBuilder.capabilityReminder(),
                    toolsBlock = toolsBlock,
                    historyBlock = historyBlock,
                    stateInject = stateInject,
                    chatHint = chatHint,
                    formattedQuery = formattedQuery,
                )
            }
        }

        val executedTools = mutableSetOf<String>()
        executedTools.addAll(previousExecutedTools)
        val toolFeedbacks = mutableListOf<String>()
        currentPendingTools.clear()

        val stream = StreamState(turnId)
        val startTime = System.currentTimeMillis()
        val firstTokenTime = java.util.concurrent.atomic.AtomicLong(-1L)

        // Latency Masking: play a filler phrase immediately while the local LLM loads
        if (!LocalLLMActivity.isCloudModelActive && loopCount == 0 && emptyChatRetry == 0 && !isAgenticObservation) {
            val fillerPhrases = listOf(
                "Let me check...",
                "I'm on it...",
                "One moment...",
                "Let's see..."
            )
            val selectedFiller = fillerPhrases.random()
            // Using a special utterance ID so we don't accidentally terminate the turn or overlap with questions
            voiceManager.speak(selectedFiller, "LATENCY_MASK")
        }

        val onToken: (String) -> Unit = { chunkText ->
            // Invoked on LiteRT's native streaming thread. Bail out for abandoned turns so a
            // stale inference cannot overwrite the UI or queue TTS for a question already gone.
            if (!stream.isHallucinating && isCurrentTurn(turnId) && !turnState.isQueryProcessed) {
                if (firstTokenTime.compareAndSet(-1L, System.currentTimeMillis())) {
                    LatencyLogger.log("Orchestrator", "TTFT: ${firstTokenTime.get() - startTime}ms")
                }

                var currentText = stream.append(chunkText)
                currentText = StreamTextPolicy.scrubStreamChunk(currentText)
                stream.replace(currentText)

                val (cutText, hallucinated) = StreamTextPolicy.cutHallucinatedUserEcho(currentText)
                if (hallucinated) {
                    stream.markHallucinating()
                    currentText = cutText
                    stream.replace(currentText)
                }

                if (currentText.length > AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH) {
                    if (StreamTextPolicy.isRunawayGeneration(currentText)) {
                        stream.markHallucinating()
                    }
                }

                val displayMsg = StreamTextPolicy.normalizeForDisplay(ToolCallParser.stripToolTags(currentText))

                if (displayMsg.isNotEmpty()) {
                    _state.value = AgentState.Streaming(displayMsg)
                }

                var remainingText = displayMsg.substring(Math.min(stream.spokenLength, displayMsg.length))
                var match = StreamTextPolicy.SENTENCE_REGEX.find(remainingText)
                while (match != null) {
                    val sentence = match.value
                    val sentenceStartOffset = stream.consumeSentence(sentence.length)
                    voiceManager.speak(sentence, "SENTENCE_$sentenceStartOffset")

                    val nextStart = Math.min(stream.spokenLength, displayMsg.length)
                    remainingText = displayMsg.substring(nextStart)
                    match = StreamTextPolicy.SENTENCE_REGEX.find(remainingText)
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

                    conversationMemory.addTurn("Assistant", tempFinalMsg.trim())

                    var finalMsg = StreamTextPolicy.normalizeForDisplay(ToolCallParser.stripToolTags(tempFinalMsg))
                    finalMsg = StreamTextPolicy.sanitizeFinalReply(query, finalMsg)
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
                    _state.value = AgentState.Speaking(displayFinalMsg)

                    // Keep input disabled until tools (and any agentic follow-up) finish. Marking the
                    // turn complete here used to let a new query clear currentPendingTools mid-flight.

                    // Flush any remaining text to TTS FIRST before extracting tools
                    if (finalMsg.isNotBlank()) {
                        val safeIndex = Math.min(stream.spokenLength, finalMsg.length)
                        val remainingSentence = finalMsg.substring(safeIndex).trim()
                        if (remainingSentence.isNotEmpty()) {
                            val sentenceStartOffset = stream.consumeSentence(remainingSentence.length)
                            voiceManager.speak(remainingSentence, "SENTENCE_$sentenceStartOffset")
                        }
                    }

                    // Now parse tools — use a turn-local list so a newer turn cannot clear ours.
                    val pendingTools = mutableListOf<Deferred<String?>>()
                    val confirmationAsks = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val actuallyExecutedTools = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
                    
                    // Combine native tool calls with any text-parsed tool calls (for Cloud fallback)
                    val parsedTools = (nativeToolCalls + ToolCallParser.extractToolCalls(tempFinalMsg)).map {
                        ToolLoopPlanner.ParsedTool(it.toolName, it.args)
                    }
                    val planned = ToolLoopPlanner.plan(
                        tools = parsedTools,
                        userQuery = query,
                        alreadyExecuted = executedTools,
                        isAllowed = { name -> toolSchemaGenerator.isToolAllowedForCurrentPrompt(name) },
                        confirmationAsk = { toolCall, toolName ->
                            val toolDef = toolRegistry.getToolDefinition(toolCall)
                            if (toolDef?.requiresConfirmation == true) {
                                ToolLoopPlanner.confirmationAskMessage(toolName, toolDef.confirmationMessage)
                            } else {
                                null
                            }
                        },
                    )
                    val hasExecutedAnyToolThisTurn = actuallyExecutedTools.isNotEmpty()
                    
                    for (action in planned) {
                        executedTools.add(action.toolCall)
                        when (action) {
                            is ToolLoopPlanner.PlannedToolAction.RejectCrisisEntertainment -> {
                                android.util.Log.w(TAG, "Crisis turn blocked entertainment tool: ${action.toolCall}")
                                toolFeedbacks.add(action.spokenFeedback)
                            }
                            is ToolLoopPlanner.PlannedToolAction.RejectAllowList -> {
                                android.util.Log.w(TAG, "LLM tool rejected by allow-list: ${action.toolCall}")
                                toolFeedbacks.add(action.message)
                            }
                            is ToolLoopPlanner.PlannedToolAction.RequireConfirmation -> {
                                confirmationCoordinator.setConfirmation(action.toolCall)
                                confirmationAsks.add(action.askMessage)
                                toolFeedbacks.add(action.askMessage)
                                android.util.Log.i(TAG, "LLM tool requires confirmation; stashed ${action.toolCall}")
                            }
                            is ToolLoopPlanner.PlannedToolAction.ScheduleExecute -> {
                                val toolCall = action.toolCall
                                val result = withContext(Dispatchers.IO) {
                                    withTimeoutOrNull(AssistantConfig.Llm.TOOL_TIMEOUT_MS) {
                                        when (val decision = evaluateContextGuard(toolCall)) {
                                            is ContextGuard.Decision.Confirm -> {
                                                synchronized(confirmationCoordinator) {
                                                    if (confirmationCoordinator.pendingConfirmationTool == null) {
                                                        confirmationCoordinator.setConfirmation(decision.originalToolCall)
                                                        confirmationAsks.add(decision.message)
                                                        decision.message
                                                    } else {
                                                        "System Error: Skipped execution because another tool is already pending confirmation."
                                                    }
                                                }
                                            }
                                            is ContextGuard.Decision.Block -> decision.message
                                            is ContextGuard.Decision.Escalate -> decision.message
                                            is ContextGuard.Decision.Allow -> {
                                                val res = executeToolCall(
                                                    toolCall,
                                                    enforcePromptAllowList = true,
                                                )
                                                actuallyExecutedTools.add(toolCall)
                                                res
                                            }
                                        }
                                    } ?: "System Error: Tool execution timed out."
                                }
                                if (result != null) {
                                    toolFeedbacks.add(result)
                                }
                            }
                        }
                    }

                    if (actuallyExecutedTools.isNotEmpty() || hasExecutedAnyToolThisTurn) {
                        // Tool already ran — don't keep a soft offer pending for a later "yes".
                        if (confirmationCoordinator.pendingConfirmationTool == null) {
                            confirmationCoordinator.clearOffer()
                        }
                    }

                    if (executedTools.any { it.startsWith("handleDrowsyDriving", ignoreCase = true) }) {
                        EmergencyAlarmManager.start(context)
                    }

                    if (planned.any { it is ToolLoopPlanner.PlannedToolAction.ScheduleExecute }) {
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
                            confirmationAsks.isEmpty() &&
                            (hasError || isQueryTool || requiresAgenticLoop || !hasConversationalText)

                        if (shouldRunAgenticLoop) {
                            val feedbackString = toolFeedbacks.joinToString("\n")
                            val observation = "System Observation: Tool execution resulted in:\n$feedbackString\nIf the user's request is fully satisfied, respond to the user naturally. If you need to take another action based on this information, output another <TOOL> call."

                            conversationMemory.addTurn("Assistant", tempFinalMsg.trim())
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
                            voiceManager.speak(finalDisplayMsg, "SENTENCE_FINAL_FB")
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
                            voiceManager.speak(feedbackMsg, "SENTENCE_FINAL_CONFIRM")
                        }
                    }

                    // Update UI with the final resulting message (keep prior text on silent ignore)
                    if (finalDisplayMsg.isNotBlank()) {
                        _state.value = AgentState.Speaking(finalDisplayMsg)
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
                    voiceManager.playSilentUtterance(10, finalUtterance)

                    // Only now is the turn finished — re-enable input for the next utterance.
                    // Pending confirmation must re-open the mic even if the model already spoke prose.
                    if (spokenIsQuestion && (finalMsg.isBlank() || confirmationCoordinator.pendingConfirmationTool != null)) {
                        _events.tryEmit(AgentEffect.StartListening)
                    }
                    _events.tryEmit(AgentEffect.SetInputEnabled(true))
                    turnState.markProcessed()
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
                        _state.value = AgentState.Thinking(query)
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
                            _state.value = AgentState.Thinking()
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
                            _state.value = AgentState.Error("Hardware Recovery Failed.")
                            _events.tryEmit(AgentEffect.SetInputEnabled(true))
                            turnState.markProcessed()
                        }
                    } else {
                        if (throwable.message?.contains("Cancellation") != true) {
                            android.util.Log.e(TAG, "Inference failed after retry", throwable)
                            _state.value = AgentState.Error("Model Inference Failed: ${throwable.message}")
                            _events.tryEmit(AgentEffect.SetInputEnabled(true))
                            turnState.markProcessed()
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
            intentHandler = { intent -> intentDispatcher.queueIntent(intent) },
        )
    }

    companion object {
        private const val TAG = "AgentOrchestrator"

        private const val TAKING_ACTION_PLACEHOLDER = "Taking action..."

        /** Empathy + open offer for mild wellness — owned by [StreamTextPolicy]. */
        internal const val WELLNESS_OFFER = StreamTextPolicy.WELLNESS_OFFER

        /** Delegates to [StreamTextPolicy] so tests keep calling AgentOrchestrator.*. */
        internal fun resolveEmptyModelFallback(userQuery: String): String =
            StreamTextPolicy.resolveEmptyModelFallback(userQuery)

        internal fun looksLikeUserQuestion(userQuery: String): Boolean =
            StreamTextPolicy.looksLikeUserQuestion(userQuery)

        internal fun looksLikeActionRequest(userQuery: String): Boolean =
            StreamTextPolicy.looksLikeActionRequest(userQuery)

        internal fun normalizeForDisplay(text: String): String =
            StreamTextPolicy.normalizeForDisplay(text)

        internal fun isRunawayGeneration(text: String): Boolean =
            StreamTextPolicy.isRunawayGeneration(text)
    }
}

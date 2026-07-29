package com.tcs.vehicleassistant.repository

import android.content.Context
import android.content.Intent
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.tcs.vehicleassistant.*
import com.tcs.vehicleassistant.CloudMessageCallback
import com.tcs.vehicleassistant.core.AssistantConfig
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
    private val audioManager: com.tcs.vehicleassistant.hardware.IAudioManager
) {
    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<OrchestratorEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var isQueryProcessed = true
    private var timeoutJob: Job? = null
    private var pendingConfirmationTool: String? = null
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

    fun handleQuery(query: String, retryCount: Int = 0) {
        val trimmedQuery = query.trim()
        val lowerQuery = trimmedQuery.lowercase().replace(Regex("[^a-z ]"), "").trim()
        
        // Ghost Voice Filter: Aggressively ignore silence hallucinations from Whisper
        val ignoredHallucinations = setOf(
            "you", "thank you", "bye", "am", "i", "what", "blank audio", "thanks for watching", "a", "yeah", "yes", "ok"
        )
        
        // Let it pass if it's an internal system event (starts with '[')
        if (!trimmedQuery.startsWith("[")) {
            if (trimmedQuery.isBlank() || lowerQuery.length < 3 || ignoredHallucinations.contains(lowerQuery)) {
                _events.tryEmit(OrchestratorEvent.FinishSession)
                resetState()
                return
            }
        }

        if (!LocalLLMActivity.isCloudModelActive && !LLMManager.isReady()) {
            _state.value = OrchestratorState.Thinking(query)
            _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
            scope.launch {
                try {
                    val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin()
                        .inject(org.koin.core.qualifier.named("edge"))
                    edgeProvider.initialize(context, force = false)
                    handleQuery(query, retryCount)
                } catch (e: Exception) {
                    _state.value = OrchestratorState.Error("Model not loaded. Open the app to load a model.")
                    _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                }
            }
            return
        }

        if (pendingConfirmationTool == null && tryHandleDirectFollowUp(query)) {
            return
        }

        val turnId = beginTurn()

        _state.value = OrchestratorState.Thinking(query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))

        scope.launch {
            processQuery(query, retryCount, turnId = turnId)
        }
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
                    _events.tryEmit(OrchestratorEvent.FinishSession)
                }
                "STATEMENT_FINAL" -> {
                    delay(50)
                    _events.tryEmit(OrchestratorEvent.FinishSession)
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
                    _events.tryEmit(OrchestratorEvent.FinishSession)
                }
                "STATEMENT_FINAL" -> {
                    delay(4000) // Artificial delay to allow user to read text since TTS failed
                    _events.tryEmit(OrchestratorEvent.FinishSession)
                }
            }
        }
    }

    fun destroy() {
        timeoutJob?.cancel()
        scope.cancel()
        EmergencyAlarmManager.stop()
    }

    private fun tryHandleDirectFollowUp(query: String): Boolean {
        if (pendingConfirmationTool != null) return false
        val toolCall = FollowUpRouter.resolveDirectTool(query, LLMManager.lastAiResponse) ?: return false

        beginTurn()
        _state.value = OrchestratorState.Thinking(query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))

        scope.launch {
            MemoryManager.captureLongTermFacts(context, query)
            MemoryManager.addTurn("User", query)

            if (toolCall.startsWith("handleDrowsyDriving") || FollowUpRouter.isDrowsyDriverQuery(query)) {
                EmergencyAlarmManager.start(context)
            }

            val feedback = executeToolCall(toolCall) ?: "Action completed."
            val finalMsg = when {
                toolCall.startsWith("handleDrowsyDriving") ->
                    "Hey — stay with me! I'm cooling the cabin and cranking upbeat music to help you stay alert."
                toolCall.startsWith("stopMusic") -> "No problem, I've stopped the music for you right away."
                toolCall.startsWith("playMusic") -> "Great choice — playing music for you right away!"
                toolCall.startsWith("increaseTemperature") -> "I'm warming up the cabin for you right away!"
                toolCall.startsWith("decreaseTemperature") -> "I'm cooling down the cabin for you right away!"
                toolCall.startsWith("startNavigationTo") -> feedback
                toolCall.startsWith("searchNearby") -> feedback
                else -> feedback
            }

            MemoryManager.addTurn("Assistant", finalMsg)
            LLMManager.lastAiResponse = finalMsg
            _state.value = OrchestratorState.Speaking(finalMsg)
            _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
            isQueryProcessed = true

            if (finalMsg.isNotBlank()) {
                audioManager.speak(finalMsg, "SENTENCE_0")
                val isQuestion = finalMsg.trim().endsWith("?") ||
                    finalMsg.contains("which one", ignoreCase = true)
                val finalUtterance = if (isQuestion) "QUESTION_FINAL" else "STATEMENT_FINAL_TOOL"
                audioManager.playSilentUtterance(10, finalUtterance)
            }
        }
        return true
    }

    private suspend fun processQuery(
        query: String,
        retryCount: Int,
        loopCount: Int = 0,
        isAgenticObservation: Boolean = false,
        previousExecutedTools: Set<String> = emptySet(),
        turnId: Long
    ) {
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

        if (pendingConfirmationTool != null) {
            val q = query.lowercase()
            if (q.contains("yes") || q.contains("yeah") || q.contains("sure") || q.contains("do it") || q.contains("ok")) {
                val toolToExecute = pendingConfirmationTool!!
                pendingConfirmationTool = null
                val feedback = executeToolCall(toolToExecute)
                interceptedQuery = "System: Executed $toolToExecute. Result: $feedback. User originally said 'yes'."
            } else {
                pendingConfirmationTool = null
                interceptedQuery = "System: Action aborted by user. User originally said: $query"
            }
        }

        val finalPrompt: String = withContext(Dispatchers.IO) {
            val toolManager = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>()
            val maxHistoryChars = toolManager.slidingWindowMaxChars
            val isFollowUp = MemoryManager.isFollowUpQuery(interceptedQuery)
            val historyCap = if (isFollowUp || interceptedQuery.length < 30) maxHistoryChars else minOf(1000, maxHistoryChars)
            val priorHistory = MemoryManager.getSlidingWindowContext(historyCap)

            if (isAgenticObservation) {
                MemoryManager.addTurn("System", interceptedQuery)
            } else {
                MemoryManager.captureLongTermFacts(context, interceptedQuery)
                MemoryManager.addTurn("User", interceptedQuery)
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

            val stateInject = if (vehicleState.isNotEmpty() && vehicleState != LLMManager.lastVehicleState) {
                LLMManager.lastVehicleState = vehicleState
                "$vehicleState\n"
            } else ""

            val isLlama = LLMManager.currentModelPath?.contains("llama", ignoreCase = true) == true && LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == false

            val historyBlock = if (priorHistory.isNotEmpty()) {
                "\n[Recent Conversation]\n$priorHistory\n"
            } else {
                ""
            }

            if (isLlama) {
                val relevantToolsList = toolManager.getRelevantTools(interceptedQuery, LLMManager.lastAiResponse)
                val toolsJsonArr = relevantToolsList.map { "\"${it.handlerKey}\"" }.joinToString(",", "[", "]")
                """{"user_input":"${interceptedQuery.replace("\"", "\\\"")}","available_tools":$toolsJsonArr,"vehicle_context":{},"dialog_state":{}}"""
            } else {
                val formattedQuery = if (LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == true) {
                    "User: $interceptedQuery"
                } else {
                    interceptedQuery
                }

                // Tools and identity rules are query-dependent. On turn 1 they ride inside the full
                // system prompt; on later turns LiteRT only sees the bare user text unless we
                // re-inject them — which is when the model starts saying it is a text-only AI that
                // cannot play music.
                val toolsForTurn = toolManager.getLlmToolsPrompt(interceptedQuery, LLMManager.lastAiResponse)
                val toolsBlock = if (toolsForTurn.isNotBlank()) {
                    "=== AVAILABLE TOOLS ===\n$toolsForTurn\n"
                } else {
                    ""
                }

                if (LLMManager.isFirstMessage) {
                    LLMManager.isFirstMessage = false
                    "$sysPrompt\n$stateInject\n$formattedQuery".trim()
                } else {
                    buildString {
                        append(LLMManager.capabilityReminder())
                        append(toolsBlock)
                        if (stateInject.isNotBlank()) append(stateInject)
                        append(formattedQuery)
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

        val onDone: (String) -> Unit = {
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

                    val isQuestion = finalMsg.trim().endsWith("?") ||
                        finalMsg.contains("would you like", ignoreCase = true) ||
                        finalMsg.contains("if you'd like", ignoreCase = true) ||
                        finalMsg.contains("do you want", ignoreCase = true) ||
                        finalMsg.contains("shall i", ignoreCase = true)

                    LLMManager.lastAiResponse = finalMsg
                    
                    // Don't emit empty finalMsg to avoid clearing the UI
                    val displayFinalMsg = if (finalMsg.isBlank()) TAKING_ACTION_PLACEHOLDER else finalMsg
                    _state.value = OrchestratorState.Speaking(displayFinalMsg)
                    
                    _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                    isQueryProcessed = true

                    // Flush any remaining text to TTS FIRST before extracting tools
                    if (finalMsg.isNotBlank()) {
                        val safeIndex = Math.min(stream.spokenLength, finalMsg.length)
                        val remainingSentence = finalMsg.substring(safeIndex).trim()
                        if (remainingSentence.isNotEmpty()) {
                            val sentenceStartOffset = stream.consumeSentence(remainingSentence.length)
                            audioManager.speak(remainingSentence, "SENTENCE_$sentenceStartOffset")
                        }
                    } else {
                        val isQ = isQuestion || finalMsg.contains("?") ||
                            finalMsg.contains("would you like", ignoreCase = true) ||
                            finalMsg.contains("could you", ignoreCase = true)
                        if (isQ) {
                            _events.tryEmit(OrchestratorEvent.StartListening)
                        }
                    }

                    // Now parse tools
                    val parsedTools = ToolCallParser.extractToolCalls(tempFinalMsg)
                    for (parsed in parsedTools) {
                        val toolCall = "${parsed.toolName}(${parsed.args})"
                        if (executedTools.add(toolCall)) {
                            val toolDef = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().getToolDefinition(toolCall)
                            if (toolDef?.requiresConfirmation == true) {
                                pendingConfirmationTool = toolCall
                            } else {
                                // Launched in the orchestrator's own scope so destroy() cancels
                                // pending tool work instead of orphaning it in a detached scope.
                                val job = scope.async(Dispatchers.IO) {
                                    audioManager.waitUntilFinishedSpeaking()
                                    withTimeoutOrNull(AssistantConfig.Llm.TOOL_TIMEOUT_MS) {
                                        executeToolCall(toolCall)
                                    } ?: "System Error: Tool execution timed out."
                                }
                                currentPendingTools.add(job)
                            }
                        }
                    }

                    if (currentPendingTools.isNotEmpty()) {
                        // Do NOT emit Thinking here, as it clears the UI screen and causes flickering.
                        val feedbacks = awaitAll(*currentPendingTools.toTypedArray()).filterNotNull()
                        toolFeedbacks.addAll(feedbacks)
                    }

                    if (executedTools.any { it.startsWith("handleDrowsyDriving", ignoreCase = true) }) {
                        EmergencyAlarmManager.start(context)
                    }

                    if (currentPendingTools.isNotEmpty()) {
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
                        val requiresAgenticLoop = executedTools.any { org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().getToolDefinition(it)?.requiresAgenticLoop == true }
                        val shouldRunAgenticLoop = isAgenticLoopEnabled &&
                            loopCount < AssistantConfig.Llm.MAX_AGENTIC_LOOPS &&
                            (hasError || isQueryTool || requiresAgenticLoop || (!hasConversationalText && !isTerminalTool))

                        if (shouldRunAgenticLoop) {
                            val feedbackString = toolFeedbacks.joinToString("\n")
                            val observation = "System Observation: Tool execution resulted in:\n$feedbackString\nIf the user's request is fully satisfied, respond to the user naturally. If you need to take another action based on this information, output another <TOOL> call."

                            currentPendingTools.clear()
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

                    // Append error feedback if needed, but DO NOT speak it automatically if we already spoke the main text.
                    var finalDisplayMsg = finalMsg
                    if (finalMsg.isEmpty() || finalMsg == TAKING_ACTION_PLACEHOLDER) {
                        finalDisplayMsg = if (toolFeedbacks.isNotEmpty()) {
                            toolFeedbacks.distinct().joinToString(" ")
                        } else {
                            GENERIC_ACTION_ACK
                        }
                        // Speak it since it wasn't spoken earlier
                        audioManager.speak(finalDisplayMsg, "SENTENCE_FINAL_FB")
                    } else if (toolFeedbacks.isNotEmpty()) {
                        val hasError = toolFeedbacks.any {
                            it.contains("Error", true) || it.contains("Failed", true) ||
                            it.contains("couldn't", true) || it.contains("didn't confirm", true)
                        }
                        if (hasError) {
                            val errorMsg = toolFeedbacks.joinToString(" ")
                            finalDisplayMsg += " " + errorMsg
                            audioManager.speak(errorMsg, "SENTENCE_FINAL_ERR")
                        }
                    }

                    // Update UI with the final resulting message
                    _state.value = OrchestratorState.Speaking(finalDisplayMsg)

                    val finalUtterance = if (isQuestion) "QUESTION_FINAL"
                        else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL"
                        else "STATEMENT_FINAL"
                    audioManager.playSilentUtterance(10, finalUtterance)
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
                    
                    if ((LLMManager.currentModelPath.contains("gemma", ignoreCase = true) ||
                            LLMManager.currentModelPath.contains("handoff", ignoreCase = true)) &&
                        MemoryManager.turnCount() > AssistantConfig.Llm.CONVERSATION_RESET_TURNS
                    ) {
                        LLMManager.resetConversation(context)
                        MemoryManager.clearMemory()
                    }

                    edgeProvider.generateStream(context, finalPrompt, interceptedQuery, onToken, onDone, onError)
                }
            } catch (e: Exception) {
                if (!e.message.toString().contains("Cancellation")) {
                    onError(e)
                }
            }
        }
    }

    private suspend fun executeToolCall(toolCall: String): String? {
        val toolManager = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>()
        return toolManager.executeToolCall(context.applicationContext, toolCall) { intent ->
            pendingIntentsToLaunch.add(intent)
        }
    }

    companion object {
        private const val TAG = "AgentOrchestrator"

        private const val TAKING_ACTION_PLACEHOLDER = "Taking action..."
        private const val GENERIC_ACTION_ACK = "Done — that's taken care of."

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

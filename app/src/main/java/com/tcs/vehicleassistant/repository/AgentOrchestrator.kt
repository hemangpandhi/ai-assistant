package com.tcs.vehicleassistant.repository

import android.content.Context
import android.content.Intent
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.tcs.vehicleassistant.*
import com.tcs.vehicleassistant.CloudMessageCallback
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
    private val memory: com.tcs.vehicleassistant.data.memory.ConversationMemory =
        com.tcs.vehicleassistant.data.memory.MemoryManagerStore(),
    private val featureFlags: com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags =
        com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags(context),
) {
    private val speechPresenter = com.tcs.vehicleassistant.domain.SpeechPresenter(audioManager)
    private val followUpUseCase = com.tcs.vehicleassistant.domain.FollowUpUseCase()

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<OrchestratorEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isQueryProcessed = true
    private var timeoutJob: Job? = null
    private var pendingConfirmationTool: String? = null
    private val pendingIntentsToLaunch = mutableListOf<Intent>()
    private val lastResponseBuilder = StringBuilder()

    @Volatile var ttsSpokenLength = 0
        private set
    @Volatile var lastTtsUpdateTime = 0L
        private set

    private val currentPendingTools = mutableListOf<Deferred<String?>>()
    private var pendingPrewarmQuery: Pair<String, Int>? = null
    private var prewarmWaitJob: Job? = null

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

        if (LLMManager.isPrewarming) {
            pendingPrewarmQuery = query to retryCount
            _state.value = OrchestratorState.Thinking
            _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
            if (prewarmWaitJob?.isActive != true) {
                prewarmWaitJob = scope.launch {
                    while (LLMManager.isPrewarming) {
                        delay(50)
                    }
                    val queued = pendingPrewarmQuery
                    pendingPrewarmQuery = null
                    if (queued != null) {
                        handleQuery(queued.first, queued.second)
                    }
                }
            }
            return
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

        lastResponseBuilder.clear()
        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        isQueryProcessed = false
        speechPresenter.reset()

        _state.value = OrchestratorState.Thinking(query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))

        scope.launch {
            processQuery(query, retryCount)
        }
    }

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
        val toolCall = followUpUseCase.resolve(query, LLMManager.lastAiResponse) ?: return false

        lastResponseBuilder.clear()
        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        isQueryProcessed = false
        _state.value = OrchestratorState.Thinking(query)
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))

        scope.launch {
            memory.captureLongTermFacts(context, query)
            memory.addTurn("User", query)

            if (toolCall.startsWith("handleDrowsyDriving") || FollowUpRouter.isDrowsyDriverQuery(query)) {
                EmergencyAlarmManager.start(context)
            }

            val feedback = executeToolCall(toolCall) ?: "Action completed."
            val finalMsg = when {
                toolCall.startsWith("handleDrowsyDriving") ->
                    "Hey — stay with me! I'm cooling the cabin and cranking upbeat music to help you stay alert."
                toolCall.startsWith("startNavigationTo") -> feedback
                toolCall.startsWith("searchNearby") -> feedback
                else -> feedback
            }

            memory.addTurn("Assistant", finalMsg)
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
        previousExecutedTools: Set<String> = emptySet()
    ) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            val timeoutDuration = if (LLMManager.isFirstMessage) 180000L else 45000L
            delay(timeoutDuration)
            if (!isQueryProcessed) {
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
            val isFollowUp = memory.isFollowUpQuery(interceptedQuery)
            val historyCap = if (isFollowUp || interceptedQuery.length < 30) maxHistoryChars else minOf(1000, maxHistoryChars)
            val priorHistory = memory.getSlidingWindowContext(historyCap)

            if (isAgenticObservation) {
                memory.addTurn("System", interceptedQuery)
            } else {
                memory.captureLongTermFacts(context, interceptedQuery)
                memory.addTurn("User", interceptedQuery)
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
                if (isHandoffModel) "[System Context: $dynamicState]" else "[Current State: $dynamicState]"
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
                
                if (LLMManager.isFirstMessage) {
                    LLMManager.isFirstMessage = false
                    "$sysPrompt$stateInject\n$formattedQuery".trim()
                } else {
                    "$stateInject\n$formattedQuery".trim()
                }
            }
        }

        val executedTools = mutableSetOf<String>()
        executedTools.addAll(previousExecutedTools)
        val toolFeedbacks = mutableListOf<String>()
        currentPendingTools.clear()
        val spokenTextLength = intArrayOf(0)
        val parsedSpokenLength = intArrayOf(0)

        val startTime = System.currentTimeMillis()
        var firstTokenTime = -1L

        var isHallucinating = false
        var isDoneCalled = false

        val onToken: (String) -> Unit = { chunkText ->
            if (!isHallucinating) {
                if (firstTokenTime == -1L) {
                    firstTokenTime = System.currentTimeMillis()
                    LatencyLogger.log("Orchestrator", "TTFT: ${firstTokenTime - startTime}ms")
                }

                if (!isQueryProcessed) {
                    lastResponseBuilder.append(chunkText)
                    var currentText = lastResponseBuilder.toString()

                    var stripped = true
                    while (stripped) {
                        stripped = false
                        val prefixes = listOf(
                            "Assistant:", "Response:", "User:", "Assistant :", "Response :", "User :", "System:", "System :",
                            "<start_of_turn>", "<end_of_turn>", "model\n", "user\n", "model", "user"
                        )
                        for (prefix in prefixes) {
                            if (currentText.trimStart().startsWith(prefix, ignoreCase = true)) {
                                currentText = currentText.trimStart().substring(prefix.length).trimStart()
                                lastResponseBuilder.clear()
                                lastResponseBuilder.append(currentText)
                                stripped = true
                            }
                        }
                    }
                    
                    // Robust cleanup for any trailing, inline, or mangled special tokens (do NOT strip tool tags here)
                    currentText = currentText.replace(Regex("(?i)<start_of_turn>|<end_of_turn>|start_of_turn|end_of_turn|start of turn|end of turn"), "")
                                             .replace(Regex("(?i)\\bmodel\\b\\n?|\\buser\\b\\n?"), "")
                    lastResponseBuilder.clear()
                    lastResponseBuilder.append(currentText)

                    val userIdx = currentText.indexOf("\nUser:")
                    if (userIdx != -1) {
                        isHallucinating = true
                        currentText = currentText.substring(0, userIdx)
                        lastResponseBuilder.setLength(userIdx)
                    } else if (currentText.trim().endsWith("User:")) {
                        isHallucinating = true
                        currentText = currentText.substringBeforeLast("User:")
                        lastResponseBuilder.setLength(currentText.length)
                    }

                    if (currentText.length > 250) {
                        val lastWords = currentText.trim().split(Regex("\\s+")).takeLast(5)
                        val isRepeating = lastWords.size == 5 && lastWords.distinct().size == 1
                        val isRunaway = currentText.length > 600

                        if (isRepeating || isRunaway) {
                            isHallucinating = true
                        }
                    }

                    var displayMsg = ToolCallParser.stripToolTags(currentText)
                    displayMsg = displayMsg.replace(Regex("\\biI\\b"), "I")
                    displayMsg = displayMsg.replace(Regex("\\bi can I\\b", RegexOption.IGNORE_CASE), "I can")
                    displayMsg = displayMsg.replace(Regex("^i\\s+"), "")
                    displayMsg = displayMsg.replace(Regex("^i\\b"), "I")
                    displayMsg = displayMsg.trim()

                    if (displayMsg.isNotEmpty()) {
                        _state.value = OrchestratorState.Streaming(displayMsg)
                    }

                    // Eager mid-stream tool execution as soon as </TOOL> closes.
                    val completeTools = ToolCallParser.extractCompleteToolCalls(currentText)
                    for (parsed in completeTools) {
                        val toolCall = parsed.invocation
                        if (executedTools.add(toolCall)) {
                            scheduleEagerTool(toolCall)
                        }
                    }

                    val safeStartIndex = Math.min(spokenTextLength[0], displayMsg.length)
                    var remainingText = displayMsg.substring(safeStartIndex)
                    val sentenceRegex = "^(.*?)([.!?]{2,}(?:\\s+|$)|\\n|(?<=[a-zA-Z\\)\\]\\\"])[.,!?](?:\\s+|$))".toRegex()
                    var match = sentenceRegex.find(remainingText)
                    while (match != null) {
                        val sentence = match.value
                        spokenTextLength[0] += sentence.length
                        val sentenceStartOffset = parsedSpokenLength[0]
                        parsedSpokenLength[0] += sentence.length

                        audioManager.speak(sentence, "SENTENCE_$sentenceStartOffset")

                        remainingText = displayMsg.substring(spokenTextLength[0])
                        match = sentenceRegex.find(remainingText)
                    }
                }
            }
        }

        val onDone: (String) -> Unit = {
            if (!isDoneCalled) {
                isDoneCalled = true

                val tempFinalMsg = lastResponseBuilder.toString()
                
                scope.launch {
                    timeoutJob?.cancel()

                    if (FollowUpRouter.responseRequestsAlarm(tempFinalMsg)) {
                        EmergencyAlarmManager.start(context)
                    }

                    var finalMsg = lastResponseBuilder.toString()
                    MemoryManager.addTurn("Assistant", finalMsg.trim())

                    finalMsg = ToolCallParser.stripToolTags(finalMsg)
                    finalMsg = finalMsg.replace(Regex("\\biI\\b"), "I")
                    finalMsg = finalMsg.replace(Regex("\\bi can I\\b", RegexOption.IGNORE_CASE), "I can")
                    finalMsg = finalMsg.replace(Regex("^i\\s+"), "")
                    finalMsg = finalMsg.replace(Regex("^i\\b"), "I")
                    finalMsg = finalMsg.trim()

                    val isQuestion = finalMsg.trim().endsWith("?") ||
                        finalMsg.contains("would you like", ignoreCase = true) ||
                        finalMsg.contains("if you'd like", ignoreCase = true) ||
                        finalMsg.contains("do you want", ignoreCase = true) ||
                        finalMsg.contains("shall i", ignoreCase = true)

                    LLMManager.lastAiResponse = finalMsg
                    
                    // Don't emit empty finalMsg to avoid clearing the UI
                    val displayFinalMsg = if (finalMsg.isBlank()) "Taking action..." else finalMsg
                    _state.value = OrchestratorState.Speaking(displayFinalMsg)
                    
                    _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                    isQueryProcessed = true

                    // Flush any remaining text to TTS FIRST before extracting tools
                    if (finalMsg.isNotBlank()) {
                        val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                        val remainingSentence = finalMsg.substring(safeIndex).trim()
                        if (remainingSentence.isNotEmpty()) {
                            val sentenceStartOffset = parsedSpokenLength[0]
                            parsedSpokenLength[0] += remainingSentence.length
                            audioManager.speak(remainingSentence, "SENTENCE_$sentenceStartOffset")
                            spokenTextLength[0] += remainingSentence.length // mark as spoken
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
                        val toolCall = parsed.invocation
                        if (executedTools.add(toolCall)) {
                            val toolDef = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().getToolDefinition(toolCall)
                            if (toolDef?.requiresConfirmation == true) {
                                pendingConfirmationTool = toolCall
                            } else {
                                val job = CoroutineScope(Dispatchers.IO).async {
                                    audioManager.waitUntilFinishedSpeaking()
                                    withTimeoutOrNull(10000L) {
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
                        val isAgenticLoopEnabled = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .getBoolean("agentic_loop_enabled", true)

                        val rawResponse = lastResponseBuilder.toString()
                        val responseWithoutTags = com.tcs.vehicleassistant.utils.ToolCallParser.stripToolTags(rawResponse)
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
                        val shouldRunAgenticLoop = isAgenticLoopEnabled && loopCount < 3 &&
                            (hasError || isQueryTool || requiresAgenticLoop || (!hasConversationalText && !isTerminalTool))

                        if (shouldRunAgenticLoop) {
                            val feedbackString = toolFeedbacks.joinToString("\n")
                            val observation = "System Observation: Tool execution resulted in:\n$feedbackString\nIf the user's request is fully satisfied, respond to the user naturally. If you need to take another action based on this information, output another <TOOL> call."

                            currentPendingTools.clear()
                            lastResponseBuilder.clear()
                            processQuery(observation, retryCount, loopCount + 1, isAgenticObservation = true, previousExecutedTools = executedTools)
                            return@launch
                        }
                    }

                    // Append error feedback if needed, but DO NOT speak it automatically if we already spoke the main text.
                    var finalDisplayMsg = finalMsg
                    if (finalMsg.isEmpty() || finalMsg == "Taking action...") {
                        finalDisplayMsg = if (toolFeedbacks.isNotEmpty()) {
                            toolFeedbacks.joinToString(" ")
                        } else {
                            "On it — give me just a moment."
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
            if (!isDoneCalled) {
                isDoneCalled = true
                scope.launch {
                    timeoutJob?.cancel()
                    if (retryCount < 1) {
                        _state.value = OrchestratorState.Error("Initializing model...")
                        try {
                            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val cloudFallbackEnabled = prefs.getBoolean("cloud_fallback_enabled", false)
                            
                            if (LocalLLMActivity.isCloudModelActive) {
                                val cloudProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("cloud"))
                                cloudProvider.initialize(context, force = true)
                            } else {
                                val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("edge"))
                                edgeProvider.initialize(context, force = true)
                            }
                            _state.value = OrchestratorState.Thinking()
                            processQuery(query, retryCount + 1, loopCount, isAgenticObservation, previousExecutedTools)
                        } catch (e: Exception) {
                            _state.value = OrchestratorState.Error("Hardware Recovery Failed.")
                            _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                            isQueryProcessed = true
                        }
                    } else {
                        if (throwable.message?.contains("Cancellation") != true) {
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
                if (featureFlags.isCloudActive) {
                    val cloudProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("cloud"))
                    cloudProvider.initialize(context, force = false)
                    cloudProvider.generateStream(context, finalPrompt, interceptedQuery, onToken, onDone, onError)
                } else {
                    val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("edge"))
                    edgeProvider.initialize(context, force = false)
                    
                    if ((LLMManager.currentModelPath?.contains("gemma", ignoreCase = true) == true || 
                        LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == true) && 
                        MemoryManager.turnCount() > 8) {
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

    private fun scheduleEagerTool(toolCall: String) {
        val toolDef = org.koin.java.KoinJavaComponent.getKoin()
            .get<com.tcs.vehicleassistant.ToolManager>().getToolDefinition(toolCall)
        if (toolDef?.requiresConfirmation == true) {
            pendingConfirmationTool = toolCall
            return
        }
        val job = CoroutineScope(Dispatchers.IO).async {
            withTimeoutOrNull(10000L) {
                executeToolCall(toolCall)
            } ?: "System Error: Tool execution timed out."
        }
        currentPendingTools.add(job)
        LatencyLogger.log("Orchestrator", "Eager tool scheduled: $toolCall")
    }

    private suspend fun executeToolCall(toolCall: String): String? {
        val toolManager = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>()
        return toolManager.executeToolCall(context.applicationContext, toolCall) { intent ->
            pendingIntentsToLaunch.add(intent)
        }
    }
}

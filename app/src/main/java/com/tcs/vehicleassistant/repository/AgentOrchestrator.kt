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
import com.tcs.vehicleassistant.utils.MoodTagParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class OrchestratorState {
    object Idle : OrchestratorState()
    object Thinking : OrchestratorState()
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
    /** Optional LLM / heuristic emotion — UI merges with harness pipeline mood. */
    data class AffectiveMood(
        val mood: com.assistant.ui.assistant.api.AssistantMoodId,
    ) : OrchestratorEvent()
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

    /** Off-Main agent work — never contend with Compose / STT. */
    private val scope = com.tcs.vehicleassistant.core.AgentRuntime.scope
    private var isQueryProcessed = true
    private var timeoutJob: Job? = null
    private var pendingConfirmationTool: String? = null
    private var pendingIntentToLaunch: Intent? = null
    private val lastResponseBuilder = StringBuilder()

    /** Coalesce Streaming UI emits (~30fps) so Compose is not flooded per token. */
    @Volatile private var pendingStreamDisplay: String? = null
    @Volatile private var lastStreamEmitMs = 0L
    private val streamEmitLock = Any()

    @Volatile var ttsSpokenLength = 0
        private set
    @Volatile var lastTtsUpdateTime = 0L
        private set

    private val currentPendingTools = mutableListOf<Deferred<String?>>()
    private var pendingPrewarmQuery: Pair<String, Int>? = null
    private var prewarmWaitJob: Job? = null

    private fun emitStreamingUi(displayMsg: String, force: Boolean = false) {
        synchronized(streamEmitLock) {
            pendingStreamDisplay = displayMsg
            val now = System.currentTimeMillis()
            if (force || now - lastStreamEmitMs >= STREAM_UI_COALESCE_MS) {
                lastStreamEmitMs = now
                pendingStreamDisplay = null
                _state.value = OrchestratorState.Streaming(displayMsg)
            }
        }
    }

    private fun flushPendingStreamingUi() {
        synchronized(streamEmitLock) {
            pendingStreamDisplay?.let {
                pendingStreamDisplay = null
                lastStreamEmitMs = System.currentTimeMillis()
                _state.value = OrchestratorState.Streaming(it)
            }
        }
    }

    companion object {
        private const val STREAM_UI_COALESCE_MS = 32L
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
        // Zero-LLM path first — never block capture/response on model warm-up.
        if (pendingConfirmationTool == null && tryHandleDirectFollowUp(query)) {
            return
        }

        // Queue instead of rejecting while KV cache prewarm is in progress.
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

        if (!featureFlags.isCloudActive && !LLMManager.isReady()) {
            pendingPrewarmQuery = query to retryCount
            _state.value = OrchestratorState.Thinking
            _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))
            scope.launch {
                try {
                    val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin()
                        .inject(org.koin.core.qualifier.named("edge"))
                    edgeProvider.initialize(context, force = false)
                    val queued = pendingPrewarmQuery
                    pendingPrewarmQuery = null
                    if (queued != null) {
                        handleQuery(queued.first, queued.second)
                    }
                } catch (e: Exception) {
                    pendingPrewarmQuery = null
                    _state.value = OrchestratorState.Error("Model not loaded. Open the app to load a model.")
                    _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                }
            }
            return
        }

        lastResponseBuilder.clear()
        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        isQueryProcessed = false
        speechPresenter.reset()

        _state.value = OrchestratorState.Thinking
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
                    pendingIntentToLaunch?.let { intent ->
                        _events.tryEmit(OrchestratorEvent.LaunchIntent(intent))
                        pendingIntentToLaunch = null
                    }
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
                    pendingIntentToLaunch?.let { intent ->
                        _events.tryEmit(OrchestratorEvent.LaunchIntent(intent))
                        pendingIntentToLaunch = null
                    }
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
        prewarmWaitJob?.cancel()
        EmergencyAlarmManager.stop()
        // Shared AgentRuntime.scope is owned by VehicleAgentService — do not cancel it here.
    }

    private fun tryHandleDirectFollowUp(query: String): Boolean {
        if (pendingConfirmationTool != null) return false
        val toolCall = followUpUseCase.resolve(query, LLMManager.lastAiResponse) ?: return false

        lastResponseBuilder.clear()
        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
        isQueryProcessed = false
        speechPresenter.reset()
        _state.value = OrchestratorState.Thinking
        _events.tryEmit(OrchestratorEvent.SetInputEnabled(false))

        scope.launch {
            memory.captureLongTermFacts(context, query)
            memory.addTurn("User", query)

            if (toolCall.startsWith("handleDrowsyDriving") || FollowUpRouter.isDrowsyDriverQuery(query)) {
                EmergencyAlarmManager.start(context)
            }

            val feedback = executeToolCall(toolCall) ?: "Action completed."
            MoodTagParser.heuristicForTool(toolCall, query)?.let { mood ->
                _events.tryEmit(OrchestratorEvent.AffectiveMood(mood))
            }
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
            val needsTelemetry = !isAgenticObservation && (interceptedQuery.length >= 40 || isFollowUp)
            val dynamicState = if (needsTelemetry) {
                SmartContextInjector.getInjectedContext(interceptedQuery, context)
            } else {
                ""
            }
            val vehicleState = if (dynamicState.isNotEmpty()) "[Current State: $dynamicState]" else ""

            val stateInject = if (vehicleState.isNotEmpty() && vehicleState != LLMManager.lastVehicleState) {
                LLMManager.lastVehicleState = vehicleState
                "$vehicleState\n"
            } else ""

            val currentToolsString = toolManager.getLlmToolsPrompt(interceptedQuery, LLMManager.lastAiResponse)
            val toolsInject = if (currentToolsString.isNotBlank() && currentToolsString != LLMManager.lastInjectedTools) {
                LLMManager.lastInjectedTools = currentToolsString
                "\n[Available Tools]\n$currentToolsString\n"
            } else {
                ""
            }

            val historyBlock = if (priorHistory.isNotEmpty()) {
                "\n[Recent Conversation]\n$priorHistory\n"
            } else {
                ""
            }

            if (featureFlags.isCloudActive) {
                "$sysPrompt\n\n[Conversation History]\n${if (priorHistory.isNotEmpty()) priorHistory else "(start)"}\n\n$vehicleState\n${if (currentToolsString.isNotBlank()) "\n[Available Tools]\n$currentToolsString\n" else ""}User: $interceptedQuery\nAssistant:"
            } else {
                "$stateInject$toolsInject$interceptedQuery"
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
                        val prefixes = listOf("Assistant:", "Response:", "User:", "Assistant :", "Response :", "User :", "System:", "System :")
                        for (prefix in prefixes) {
                            if (currentText.trimStart().startsWith(prefix, ignoreCase = true)) {
                                currentText = currentText.trimStart().substring(prefix.length).trimStart()
                                lastResponseBuilder.clear()
                                lastResponseBuilder.append(currentText)
                                stripped = true
                            }
                        }
                    }

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
                    displayMsg = MoodTagParser.stripMoodTags(displayMsg)
                    displayMsg = displayMsg.replace(Regex("\\biI\\b"), "I")
                    displayMsg = displayMsg.replace(Regex("\\bi can I\\b", RegexOption.IGNORE_CASE), "I can")
                    displayMsg = displayMsg.replace(Regex("^i\\s+"), "")
                    displayMsg = displayMsg.replace(Regex("^i\\b"), "I")
                    displayMsg = displayMsg.trim()

                    if (displayMsg.isNotEmpty()) {
                        emitStreamingUi(displayMsg)
                    }

                    MoodTagParser.extractAffectiveMood(currentText)?.let { mood ->
                        _events.tryEmit(OrchestratorEvent.AffectiveMood(mood))
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
                    flushPendingStreamingUi()

                    if (FollowUpRouter.responseRequestsAlarm(tempFinalMsg)) {
                        EmergencyAlarmManager.start(context)
                    }

                    val parsedTools = ToolCallParser.extractCompleteToolCalls(tempFinalMsg)
                    for (parsed in parsedTools) {
                        val toolCall = parsed.invocation
                        if (executedTools.add(toolCall)) {
                            scheduleEagerTool(toolCall)
                        }
                    }

                    if (currentPendingTools.isNotEmpty()) {
                        _state.value = OrchestratorState.Thinking
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
                        val responseWithoutTags = MoodTagParser.stripMoodTags(
                            rawResponse.replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>|$)"), ""),
                        ).trim()
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

                    var finalMsg = lastResponseBuilder.toString()
                    memory.addTurn("Assistant", finalMsg.trim())

                    MoodTagParser.extractAffectiveMood(finalMsg)?.let { mood ->
                        _events.tryEmit(OrchestratorEvent.AffectiveMood(mood))
                    }

                    finalMsg = ToolCallParser.stripToolTags(finalMsg)
                    finalMsg = MoodTagParser.stripMoodTags(finalMsg)
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

                    if (finalMsg.isEmpty()) {
                        finalMsg = if (toolFeedbacks.isNotEmpty()) {
                            toolFeedbacks.joinToString(" ")
                        } else {
                            "On it — give me just a moment."
                        }
                    } else if (toolFeedbacks.isNotEmpty()) {
                        val hasError = toolFeedbacks.any {
                            it.contains("Error", true) || it.contains("Failed", true) ||
                            it.contains("couldn't", true) || it.contains("didn't confirm", true)
                        }
                        if (hasError) {
                            finalMsg += " " + toolFeedbacks.joinToString(" ")
                        }
                        // When the AI already gave a warm conversational response, do NOT append
                        // dry tool confirmation text — it breaks the human companion feel.
                    }

                    LLMManager.lastAiResponse = finalMsg
                    _state.value = OrchestratorState.Speaking(finalMsg)
                    _events.tryEmit(OrchestratorEvent.SetInputEnabled(true))
                    isQueryProcessed = true

                    if (finalMsg.isNotBlank()) {
                        val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                        val remainingSentence = finalMsg.substring(safeIndex).trim()
                        if (remainingSentence.isNotEmpty()) {
                            val sentenceStartOffset = parsedSpokenLength[0]
                            parsedSpokenLength[0] += remainingSentence.length
                            audioManager.speak(remainingSentence, "SENTENCE_$sentenceStartOffset")
                        }
                        val finalUtterance = if (isQuestion) "QUESTION_FINAL"
                            else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL"
                            else "STATEMENT_FINAL"
                        audioManager.playSilentUtterance(10, finalUtterance)
                    } else {
                        val isQ = isQuestion || finalMsg.contains("?") ||
                            finalMsg.contains("would you like", ignoreCase = true) ||
                            finalMsg.contains("could you", ignoreCase = true)
                        if (isQ) {
                            _events.tryEmit(OrchestratorEvent.StartListening)
                        }
                    }
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
                            if (featureFlags.isCloudActive) {
                                val cloudProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("cloud"))
                                cloudProvider.initialize(context, force = true)
                            } else {
                                val edgeProvider: ILLMProvider by org.koin.java.KoinJavaComponent.getKoin().inject(org.koin.core.qualifier.named("edge"))
                                edgeProvider.initialize(context, force = true)
                            }
                            _state.value = OrchestratorState.Thinking
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
        val job = com.tcs.vehicleassistant.core.AgentRuntime.ioScope.async {
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
            // Launch immediately so the overlay can dismiss without waiting for TTS.
            pendingIntentToLaunch = null
            _events.tryEmit(OrchestratorEvent.LaunchIntent(intent))
        }
    }
}

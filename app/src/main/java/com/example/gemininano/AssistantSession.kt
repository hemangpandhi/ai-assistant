package com.example.gemininano
import kotlinx.coroutines.*

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.Locale
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback



class AssistantSession(context: Context) : VoiceInteractionSession(context), TextToSpeech.OnInitListener {

    private lateinit var overlayView: View
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: ImageButton
    private lateinit var btnOpenApp: Button
    private lateinit var inputControls: View
    private lateinit var voiceAnimation: VoiceAnimationView
    private var svResponse: android.widget.ScrollView? = null
    
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var dotAnimatorJob: kotlinx.coroutines.Job? = null
    private var pendingConfirmationTool: String? = null
    private var currentHighlightStart = -1
    private var currentHighlightEnd = -1
    
    // Typewriter Effect Variables
    private var typewriterJob: kotlinx.coroutines.Job? = null
    private var targetDisplayMessage = ""
    private var currentDisplayLength = 0
    // Calibrated to match average Text-To-Speech rendering speed (~15 chars/sec)
    private val typingSpeedMs: Long = 65L
    
    private val currentPendingTools = mutableListOf<kotlinx.coroutines.Deferred<String?>>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    // Highlighting disabled by user request
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith("SENTENCE_")) {
                        currentHighlightStart = -1
                        currentHighlightEnd = -1
                        CoroutineScope(Dispatchers.Main).launch {
                            val spannable = responseText.text as? android.text.Spannable
                            if (spannable != null) {
                                val oldSpans = spannable.getSpans(0, spannable.length, android.text.style.BackgroundColorSpan::class.java)
                                for (span in oldSpans) spannable.removeSpan(span)
                            }
                        }
                    }
                    CoroutineScope(Dispatchers.Main).launch {
                        if (utteranceId == "QUESTION_FINAL") {
                            btnMic.performClick()
                        } else if (utteranceId == "STATEMENT_FINAL_TOOL") {
                            for (job in currentPendingTools) {
                                try { job.await() } catch (e: Exception) {}
                            }
                            kotlinx.coroutines.delay(2000)
                            finish()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    android.util.Log.e("AssistantSession", "TTS Error: " + utteranceId)
                }
            })
        }
    }

    private var currentLayoutStyle = -1
    private var speechRecognizerIntent: Intent? = null

    override fun onHide() {
        super.onHide()
        try {
            speechRecognizer?.cancel()
        } catch(e: Exception) {}
        
        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)
    }

    override fun onCreateContentView(): View {
        VehicleManager.initialize(context.applicationContext)
        tts = TextToSpeech(context, this)
        
        setupSpeechRecognizer()
        inflateAndBindLayout()
        
        return overlayView
    }
    
    private fun inflateAndBindLayout() {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val layoutStyle = prefs.getInt("ui_layout_pref", 0)
        currentLayoutStyle = layoutStyle
        
        val layoutRes = when (layoutStyle) {
            0 -> R.layout.assistant_overlay // Polestar Wide
            1 -> R.layout.assistant_overlay_pill // Center Pill
            2 -> R.layout.assistant_overlay_side // Left Side Panel
            3 -> R.layout.assistant_overlay_top // Top Banner
            4 -> R.layout.assistant_overlay_immersive // Full-Screen Immersive
            5 -> R.layout.assistant_overlay_hud // Holographic Cyberpunk HUD
            6 -> R.layout.assistant_overlay_beveled // Beveled Glass Island
            7 -> R.layout.assistant_overlay_cinematic // Cinematic Letterbox
            else -> R.layout.assistant_overlay
        }
        overlayView = layoutInflater.inflate(layoutRes, null)
        statusText = overlayView.findViewById(R.id.assistantResponseText) // Routed to main text
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)
        btnMic = overlayView.findViewById(R.id.btnMic)
        btnOpenApp = overlayView.findViewById(R.id.btnOpenApp)
        svResponse = overlayView.findViewById(R.id.svResponse)
        inputControls = overlayView.findViewById(R.id.inputControlsContainer)
        voiceAnimation = overlayView.findViewById(R.id.voiceAnimation)
        
        val modelInfoTag: android.widget.TextView? = overlayView.findViewById(R.id.modelInfoTag)
        if (modelInfoTag != null) {
            if (LocalLLMActivity.isCloudModelActive) {
                modelInfoTag.text = "${LocalLLMActivity.currentCloudModelName} ☁️"
            } else {
                val modelName = java.io.File(LLMManager.currentModelPath).nameWithoutExtension
                modelInfoTag.text = if (modelName.isNotEmpty()) modelName else "Gemma 4 E2B"
            }
        }

        // Global Adaptive Gravity Logic
        responseText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val len = s?.length ?: 0
                if (len < 50) {
                    responseText.gravity = android.view.Gravity.CENTER
                } else {
                    responseText.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                }
            }
        })

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val blurView = overlayView.findViewById<android.view.View>(R.id.blurBackgroundView)
            blurView?.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(25f, 25f, android.graphics.Shader.TileMode.CLAMP)
            )
        }
        
        val rootOverlay = overlayView.findViewById<View>(R.id.rootOverlay)
        rootOverlay.setOnClickListener {
            hide()
        }

        btnOpenApp.setOnClickListener {
            val intent = Intent(context, LocalLLMActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            hide()
        }

        btnSend.setOnClickListener {
            val query = etInput.text.toString()
            if (query.isNotBlank()) {
                tts?.stop()
                handleQuery(query)
                etInput.setText("")
            }
        }
        
        btnMic.setOnClickListener {
            tts?.stop()
            btnMic.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(500)
                speechRecognizer?.startListening(speechRecognizerIntent)
                btnMic.isEnabled = true
            }
        }
        
        // Update the active content view window with the newly inflated view
        setContentView(overlayView)
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.visibility = View.VISIBLE
                startDotAnimation("")
                voiceAnimation.state = VoiceAnimationView.State.LISTENING
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                startThinkingAnimation()
            }
            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK -> "Network Error (No Internet/Language Pack)."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network Timeout."
                    SpeechRecognizer.ERROR_NO_MATCH -> "I didn't quite catch that."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout."
                    13 -> "Offline Language Pack Missing! Type instead."
                    else -> "Voice Error: $error"
                }
                stopDotAnimation(errorMsg)
                statusText.visibility = View.VISIBLE
                voiceAnimation.state = VoiceAnimationView.State.IDLE
                
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(3000)
                    if (statusText.text == errorMsg) {
                        statusText.text = "Hi, how can I help you?"
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    etInput.setText(matches[0])
                    tts?.stop()
                    handleQuery(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    etInput.setText(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        // Re-inflate if layout setting changed
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("ui_layout_pref", 0) != currentLayoutStyle) {
            inflateAndBindLayout()
        }
        
        statusText.visibility = View.VISIBLE
        stopDotAnimation("Hi, how can I help you?")
        responseText.text = ""
        etInput.setText("")
        voiceAnimation.state = VoiceAnimationView.State.IDLE
        
        val stopListeningIntent = Intent(context, WakeWordService::class.java)
        stopListeningIntent.action = "ACTION_STOP_LISTENING"
        context.startService(stopListeningIntent)
        
        if (LLMManager.engine == null) {
            statusText.text = "Initializing Model..."
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.GONE
            
            CoroutineScope(Dispatchers.Main).launch {
                LLMManager.autoInitialize(context, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        statusText.text = "Hi, how can I help you?"
                        inputControls.visibility = View.VISIBLE
                        btnSend.isEnabled = true
                        
                        // Automatically start listening if invoked via voice match/hotword
                        if (showFlags and SHOW_WITH_ASSIST != 0) {
                            CoroutineScope(Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(500)
                                btnMic.performClick()
                            }
                        }
                    }

                    override fun onError(e: Exception) {
                        statusText.text = "Failed to load model. Please open the app."
                        btnOpenApp.visibility = View.VISIBLE
                    }
                })
            }
        } else {
            // DO NOT reset the conversation here. Resetting invalidates the KV cache 
            // and forces the LLM to re-process the massive System Prompt, causing a 2-3s delay.
            statusText.visibility = View.VISIBLE
            statusText.text = "Hi, how can I help you?"
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.VISIBLE
            btnSend.isEnabled = true
            
            // Automatically start listening if invoked via voice match/hotword
            if (showFlags and SHOW_WITH_ASSIST != 0) {
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(500)
                    btnMic.performClick()
                }
            }
        }
    }

    private var isQueryProcessed = false
    private var timeoutJob: kotlinx.coroutines.Job? = null

    private fun startThinkingAnimation() {
        statusText.visibility = View.VISIBLE
        startDotAnimation("")
        voiceAnimation.state = VoiceAnimationView.State.THINKING
    }

    private fun stopThinkingAnimation() {
        voiceAnimation.state = VoiceAnimationView.State.IDLE
        stopDotAnimation()
    }

    private suspend fun executeToolCall(toolCall: String): String? {
        return ToolManager.executeToolCall(context.applicationContext, toolCall) { intent ->
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("AssistantSession", "startActivity failed", e)
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.applicationContext.startActivity(intent)
                } catch (e2: Exception) {
                    android.util.Log.e("AssistantSession", "Fallback startActivity failed", e2)
                    throw e2 // Propagate to ToolManager for fallbacks
                }
            }
        }
    }

    private fun handleQuery(query: String, retryCount: Int = 0) {
        if (LLMManager.engine == null || LLMManager.conversation == null) return
        
        startThinkingAnimation()
        
        // Removed 'You:' and 'Assistant:' prefixes for cleaner UI
        responseText.text = ""
        currentHighlightStart = -1
        currentHighlightEnd = -1

        lastResponseBuilder.clear()
        targetDisplayMessage = ""
        currentDisplayLength = 0
        typewriterJob?.cancel()
        
        btnSend.isEnabled = false
        isQueryProcessed = false
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            processQuery(query, retryCount)
        }
    }
    
    private suspend fun processQuery(query: String, retryCount: Int, loopCount: Int = 0, isAgenticObservation: Boolean = false, previousExecutedTools: Set<String> = emptySet()) {
        // Timeout watchdog
        timeoutJob?.cancel()
        timeoutJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.delay(180000) // 3 minutes max (First-time GPU Shader Compilation / CPU Fallback)
            if (!isQueryProcessed) {
                statusText.text = "Timeout - Restarting Model..."
                responseText.text = "Please wait a moment."
                btnSend.isEnabled = false
                LLMManager.autoInitialize(context, force = true, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        statusText.text = "Hi, how can I help you?"
                        stopThinkingAnimation()
                        responseText.text = ""
                        btnSend.isEnabled = true
                    }
                    override fun onError(e: Exception) {
                        statusText.text = "Error restarting."
                        stopThinkingAnimation()
                        btnSend.isEnabled = true
                    }
                })
                kotlinx.coroutines.delay(2000)
                finish() // Auto-dismiss
            }
        }
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val diningPref = prefs.getString("dining_pref", "Pure Vegetarian") ?: "Pure Vegetarian"
        
        var interceptedQuery = query
        
        // Security: Native User Validation
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
        
        if (isAgenticObservation) {
            MemoryManager.addTurn("System", interceptedQuery)
        } else {
            MemoryManager.addTurn("User", interceptedQuery)
        }
        val slidingHistory = MemoryManager.getSlidingWindowContext(3000)
        
        // Anti-Hallucination Music Interceptor logic has been moved to the streaming loop
        // to allow the LLM to naturally generate a response.
        val qStr = interceptedQuery.lowercase()
        val expectFollowup = false
        
        val dynCtx = LLMManager.getDynamicContext(context, interceptedQuery)
        val finalPrompt: String
        if (LLMManager.isFirstMessage) {
            val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
            val reminder = "\n(Reminder: Use exact <TOOL> XML tags for car actions.)"
            
            if (slidingHistory.isNotEmpty() && !LocalLLMActivity.isCloudModelActive) {
                finalPrompt = "$sysPrompt\n$reminder$dynCtx\n\n[Conversation History]\n$slidingHistory\nUser: $interceptedQuery\nAssistant:"
            } else {
                finalPrompt = if (sysPrompt.isNotEmpty()) "$sysPrompt\n$reminder$dynCtx\n\nUser: $interceptedQuery" else "$reminder$dynCtx\n\nUser: $interceptedQuery"
            }
            LLMManager.isFirstMessage = false
        } else {
            val reminder = "\n(Reminder: Use exact <TOOL> XML tags for car actions.)"
            if (isAgenticObservation) {
                finalPrompt = "[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder$dynCtx\n$interceptedQuery"
            } else {
                finalPrompt = "[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder$dynCtx\nUser: $interceptedQuery"
            }
        }

        val executedTools = mutableSetOf<String>()
        executedTools.addAll(previousExecutedTools)
        val toolFeedbacks = mutableListOf<String>()
        currentPendingTools.clear()
        val regex = "(?i)<TOOL>(.*?)</TOOL>".toRegex()
        val spokenTextLength = intArrayOf(0)
        val parsedSpokenLength = intArrayOf(0)
        
        val startTime = System.currentTimeMillis()
        var firstTokenTime = -1L

        val callback = object : MessageCallback, CloudMessageCallback {
                        var isHallucinating = false
                        
                        override fun onMessage(message: Message) {
                            handleChunk(message.toString())
                        }
                        
                        override fun onMessage(chunkText: String) {
                            handleChunk(chunkText)
                        }

                        private fun handleChunk(chunkText: String) {
                            if (isHallucinating) return
                            
                            if (firstTokenTime == -1L) {
                                firstTokenTime = System.currentTimeMillis()
                                val ttft = firstTokenTime - startTime
                                android.util.Log.i("LLMLatency", "[AssistantSession] Time to First Token (TTFT): ${ttft}ms")
                            }
                            
                            CoroutineScope(Dispatchers.Main).launch {
                                voiceAnimation.state = VoiceAnimationView.State.SPEAKING
                                val chunk = chunkText
                                lastResponseBuilder.append(chunk)
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
                                
                                // Prevent the AI from hallucinating the user's response
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
                            
                            // Handle Tool Calls (e.g., <TOOL>increaseTemperature(2)</TOOL>)
                            val matches = regex.findAll(currentText)
                            for (match in matches) {
                                val toolCall = match.groups[1]?.value?.trim() ?: continue
                                
                                // Anti-Hallucination Hack: Prevent direct navigation on generic food queries
                                val toolName = toolCall.substringBefore("(").trim()
                                val q = interceptedQuery.lowercase()
                                val isGenericFood = (q.contains("hungry") || q.contains("food")) && 
                                                    !(q.contains("italian") || q.contains("mexican") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("vegetarian") || q.contains("vegan") || q.contains("indian") || q.contains("thai") || q.contains("japanese"))
                                
                                val isDiag = q.contains("wrong") || q.contains("broken") || q.contains("issue") || q.contains("light") || q.contains("code") || q.contains("door") || q.contains("diagnos") || q.contains("obd") || q.contains("ob2") || q.contains("engine") || q.contains("service")
                                val isShortFollowUp = q.length < 20 && !q.contains("search") && !q.contains("find") && !q.contains("look up")
                                
                                if ((toolName == "navigate" || toolName == "search") && (isGenericFood || isDiag || (toolName == "search" && isShortFollowUp))) {
                                    android.util.Log.w("AssistantSession", "Intercepted hallucinatory tool call: $toolCall")
                                    continue
                                }
                                

                                if (executedTools.add(toolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $toolCall")
                                    val toolDef = ToolManager.getToolDefinition(toolCall)
                                    if (toolDef?.requiresConfirmation == true) {
                                        pendingConfirmationTool = toolCall
                                        val confirmMsg = toolDef.confirmationMessage ?: "Warning: Are you sure you want to do this?"
                                        lastResponseBuilder.clear()
                                        lastResponseBuilder.append(confirmMsg)
                                        isHallucinating = true // Force stop further output processing
                                    } else {
                                        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                                            executeToolCall(toolCall)
                                        }
                                        currentPendingTools.add(job)
                                    }
                                }
                            }
                            // Hide any fully formed OR partially streamed tools from the TTS/UI to prevent index corruption
                            var displayMsg = currentText.replace("(?i)<TOOL>.*?(</TOOL>|$)".toRegex(), "")
                            val lastTagIndex = displayMsg.lastIndexOf("<")
                            if (lastTagIndex != -1) {
                                val potentialTag = displayMsg.substring(lastTagIndex).uppercase()
                                if ("<TOOL".startsWith(potentialTag)) {
                                    displayMsg = displayMsg.substring(0, lastTagIndex)
                                }
                            }
                            displayMsg = displayMsg.trim()
                            
                            
                            if (displayMsg.isNotEmpty() && statusText.visibility == View.VISIBLE) {
                                stopDotAnimation()
                                voiceAnimation.state = VoiceAnimationView.State.SPEAKING
                            }
                            
                            val displayStr = displayMsg.toString()
                            targetDisplayMessage = displayStr
                            
                            // Typewriter Coroutine Buffer
                            if (typewriterJob == null || typewriterJob?.isActive != true) {
                                typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                    while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                        val step = 1
                                        val dynamicDelay = typingSpeedMs
                                        
                                        currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                        val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                        
                                        responseText.text = parseMarkdown(currentSubstring)
                                        
                                        // Auto-scroll to bottom as text streams
                                        svResponse?.post {
                                            svResponse?.fullScroll(View.FOCUS_DOWN)
                                        }
                                        
                                        kotlinx.coroutines.delay(dynamicDelay)
                                    }
                                }
                            }
                            
                            // Streaming TTS Logic
                            val safeStartIndex = Math.min(spokenTextLength[0], displayMsg.length)
                            var remainingText = displayMsg.substring(safeStartIndex)
                            val sentenceRegex = "^(.*?)([.!?]{2,}(?:\\s+|$)|\\n|(?<=[a-z])[.!?](?:\\s+|$))".toRegex()
                            var match = sentenceRegex.find(remainingText)
                            while (match != null) {
                                val sentence = match.value
                                spokenTextLength[0] += sentence.length
                                
                                val parsedSentence = parseMarkdown(sentence).toString()
                                val sentenceStartOffset = parsedSpokenLength[0]
                                parsedSpokenLength[0] += parsedSentence.length
                                
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")
                                
                                remainingText = displayMsg.substring(spokenTextLength[0])
                                match = sentenceRegex.find(remainingText)
                            }
                            
                            // The typewriter handles UI updating, so we don't set it immediately here
                            // responseText.text = parseMarkdown(displayMsg)
                        }
                    }

                    override fun onDone() {
                        val totalTime = System.currentTimeMillis() - startTime
                        android.util.Log.i("LLMLatency", "[AssistantSession] Total Generation Time: ${totalTime}ms")
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            
                            if (currentPendingTools.isNotEmpty()) {
                                setKeepAwake(true)
                                // Keep the thinking animation going during tool execution and recursive loops
                                startThinkingAnimation()
                                val feedbacks = kotlinx.coroutines.awaitAll(*currentPendingTools.toTypedArray()).filterNotNull()
                                toolFeedbacks.addAll(feedbacks)
                                setKeepAwake(false)
                                
                                val isAgenticLoopEnabled = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE).getBoolean("agentic_loop_enabled", true)
                                if (isAgenticLoopEnabled && loopCount < 3) {
                                    val feedbackString = toolFeedbacks.joinToString("\n")
                                    val observation = "System Observation: Tool execution resulted in:\n$feedbackString\nIf the user's request is fully satisfied, respond to the user naturally. If you need to take another action based on this information, output another <TOOL> call."
                                    
                                    android.util.Log.i("AssistantSession", "Agentic Loop Triggered (Loop $loopCount) with observation: $observation")
                                    currentPendingTools.clear() 
                                    
                                    lastResponseBuilder.clear() 
                                    processQuery(observation, retryCount, loopCount + 1, isAgenticObservation = true, previousExecutedTools = executedTools)
                                    return@launch
                                }
                            }
                            
                            var finalMsg = lastResponseBuilder.toString()
                            
                            // Auto-Context Clearing Hack for silent KV Cache overflows
                            if (finalMsg.trim().length <= 3) {
                                if (retryCount >= 2) {
                                    statusText.text = "Error"
                                    stopThinkingAnimation()
                                    responseText.text = "The request was too large to process. Please try a simpler command."
                                    btnSend.isEnabled = true
                                    isQueryProcessed = true
                                    return@launch
                                }
                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Graceful Sliding Window Reset initiated...")
                                LLMManager.resetConversation()
                                handleQuery(query, retryCount + 1)
                                return@launch
                            }
                            
                            MemoryManager.addTurn("Assistant", finalMsg.trim())
                            
                            finalMsg = finalMsg.replace("(?i)<TOOL>.*?(</TOOL>|$)".toRegex(), "")
                            val finalLastTagIndex = finalMsg.lastIndexOf("<")
                            if (finalLastTagIndex != -1) {
                                val potentialTag = finalMsg.substring(finalLastTagIndex).uppercase()
                                if ("<TOOL".startsWith(potentialTag)) {
                                    finalMsg = finalMsg.substring(0, finalLastTagIndex)
                                }
                            }
                            finalMsg = finalMsg.trim()
                            if (finalMsg.isEmpty() && toolFeedbacks.isNotEmpty()) {
                                finalMsg = toolFeedbacks.joinToString("\n")
                            } else if (toolFeedbacks.isNotEmpty()) {
                                finalMsg += "\n\n" + toolFeedbacks.joinToString("\n")
                            }
                            
                            targetDisplayMessage = finalMsg
                            if (typewriterJob == null || typewriterJob?.isActive != true) {
                                typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                    while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                        val step = 1
                                        val dynamicDelay = typingSpeedMs
                                        currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                        val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                        responseText.text = parseMarkdown(currentSubstring)
                                        svResponse?.post { svResponse?.fullScroll(View.FOCUS_DOWN) }
                                        kotlinx.coroutines.delay(dynamicDelay)
                                    }
                                }
                            }
                            
                            stopThinkingAnimation()
                            btnSend.isEnabled = true
                            isQueryProcessed = true
                            
                            if (finalMsg.isNotBlank()) {
                                val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                                val remainingSentence = finalMsg.substring(safeIndex).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    val parsedSentence = parseMarkdown(remainingSentence).toString()
                                    val sentenceStartOffset = parsedSpokenLength[0]
                                    parsedSpokenLength[0] += parsedSentence.length
                                    tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")
                                }
                                val isQuestion = expectFollowup || 
                                                 finalMsg.trim().endsWith("?") || 
                                                 finalMsg.contains("would you like", ignoreCase = true) || 
                                                 finalMsg.contains("if you'd like", ignoreCase = true) || 
                                                 finalMsg.contains("do you want", ignoreCase = true) ||
                                                 finalMsg.contains("shall i", ignoreCase = true) ||
                                                 finalMsg.contains("let me know", ignoreCase = true) ||
                                                 finalMsg.contains("tell me", ignoreCase = true)
                                                 
                                val finalUtterance = if (isQuestion) "QUESTION_FINAL" else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL" else "STATEMENT_FINAL"
                                tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, finalUtterance)
                            } else {
                                val isQuestion = expectFollowup || 
                                                 finalMsg.trim().endsWith("?") || 
                                                 finalMsg.contains("would you like", ignoreCase = true) || 
                                                 finalMsg.contains("if you'd like", ignoreCase = true) || 
                                                 finalMsg.contains("do you want", ignoreCase = true) ||
                                                 finalMsg.contains("shall i", ignoreCase = true) ||
                                                 finalMsg.contains("let me know", ignoreCase = true) ||
                                                 finalMsg.contains("tell me", ignoreCase = true)
                                                 
                                if (isQuestion) {
                                    btnMic.performClick()
                                } else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        for (job in currentPendingTools) {
                                            try { job.await() } catch (e: Exception) {}
                                        }
                                        kotlinx.coroutines.delay(2000)
                                        finish()
                                    }
                                } else {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        kotlinx.coroutines.delay(2000)
                                        finish()
                                    }
                                }
                            }
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            android.util.Log.e("AssistantSession", "LLM Error", throwable)
                            statusText.text = "Memory full. Cleared context. Please try again."
                            stopThinkingAnimation()
                            responseText.text = "The AI context memory was full and has been cleared to prevent freezing."
                            btnSend.isEnabled = true
                            LLMManager.resetConversation()
                            isQueryProcessed = true
                        }
                    }
                }
            
        try {
            if (LocalLLMActivity.isCloudModelActive) {
                CoroutineScope(Dispatchers.IO).launch {
                    var systemPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
                    systemPrompt += "\n\n[Current State: ${VehicleManager.getLLMContextString(context)}]"
                    
                    if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                        GeminiManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    } else {
                        AnthropicManager.sendMessageAsync(systemPrompt, interceptedQuery, callback)
                    }
                }
            } else {
                LLMManager.conversation!!.sendMessageAsync(
                    Contents.of(Content.Text(finalPrompt)),
                    callback,
                    emptyMap()
                )
            }
        } catch (e: Exception) {
            statusText.text = "Error"
            stopThinkingAnimation()
            responseText.text = "An unexpected error occurred."
            btnSend.isEnabled = true
        }
    }

    private fun startDotAnimation(baseText: String) {
        dotAnimatorJob?.cancel()
        if (baseText.isEmpty()) {
            statusText.text = ""
            return
        }
        dotAnimatorJob = CoroutineScope(Dispatchers.Main).launch {
            var dotCount = 0
            while (isActive) {
                val dots = ".".repeat(dotCount)
                statusText.text = "$baseText$dots"
                dotCount = (dotCount + 1) % 4
                kotlinx.coroutines.delay(400)
            }
        }
    }

    private fun stopDotAnimation(finalText: String = "") {
        dotAnimatorJob?.cancel()
        if (finalText.isNotEmpty()) {
            statusText.text = finalText
        }
    }

    private fun parseMarkdown(text: String): android.text.SpannableStringBuilder {
        val spannable = android.text.SpannableStringBuilder()
        val parts = text.split("**")
        for (i in parts.indices) {
            val start = spannable.length
            spannable.append(parts[i])
            if (i % 2 != 0) { // Text inside ** **
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    spannable.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        dotAnimatorJob?.cancel()
        
        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)
        
        super.onDestroy()
    }
}

package com.tcs.vehicleassistant
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
import android.widget.Toast
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
    companion object {
        var globalTts: TextToSpeech? = null
    }
    private var tts: TextToSpeech?
        get() = globalTts
        set(value) { globalTts = value }
    private var speechRecognizer: SpeechRecognizer? = null
    private var dotAnimatorJob: kotlinx.coroutines.Job? = null
    private var pendingConfirmationTool: String? = null
    private var currentHighlightStart = -1
    private var currentHighlightEnd = -1
    
    // Typewriter Effect Variables
    private var typewriterJob: kotlinx.coroutines.Job? = null
    private var targetDisplayMessage = ""
    private var currentDisplayLength = 0
    @Volatile private var ttsSpokenLength = 0
    @Volatile private var lastTtsUpdateTime = 0L
    // Speed up typewriter effect significantly to fix UI sluggishness.
    private val typingSpeedMs: Long = 15L
    
    private val currentPendingTools = mutableListOf<kotlinx.coroutines.Deferred<String?>>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            
            // Force a local, offline voice to prevent a 5-second network timeout from Google TTS
            try {
                val voices = tts?.voices
                if (voices != null) {
                    for (voice in voices) {
                        if (voice.locale.language == Locale.US.language && !voice.isNetworkConnectionRequired) {
                            tts?.voice = voice
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore if getVoices() is unsupported or fails, fallback to default
            }
            
            tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, "PREWARM")
            setupTtsListener()
        }
    }

    private fun setupTtsListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null && utteranceId.startsWith("SENTENCE_")) {
                    LatencyLogger.log("AssistantSession", "TTS Synthesis Started for $utteranceId")
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != null && utteranceId.startsWith("SENTENCE_")) {
                    val sentenceStartOffset = utteranceId.substringAfter("SENTENCE_").toIntOrNull() ?: 0
                    ttsSpokenLength = Math.max(ttsSpokenLength, sentenceStartOffset + end)
                    lastTtsUpdateTime = System.currentTimeMillis()
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId.startsWith("SENTENCE_")) {
                    LatencyLogger.log("AssistantSession", "TTS Synthesis Done for $utteranceId")
                    lastTtsUpdateTime = System.currentTimeMillis()
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
                        kotlinx.coroutines.delay(500)
                        btnMic.performClick()
                    } else if (utteranceId == "STATEMENT_FINAL_TOOL") {
                        for (job in currentPendingTools) {
                            try { job.await() } catch (e: Exception) {}
                        }
                        kotlinx.coroutines.delay(50)
                        finish()
                    } else if (utteranceId == "STATEMENT_FINAL") {
                        kotlinx.coroutines.delay(50)
                        finish()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                android.util.Log.e("AssistantSession", "TTS Error: " + utteranceId)
            }
        })
    }

    private var currentLayoutStyle = -1
    private var speechRecognizerIntent: Intent? = null
    private var unloadJob: kotlinx.coroutines.Job? = null

    override fun onHide() {
        super.onHide()
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch(e: Exception) {}
        
        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)
        
        unloadJob?.cancel()
        unloadJob = CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(600_000) // Increased to 10 minutes to reduce TTFT initialization latency
            LLMManager.unload()
        }
    }

    override fun onCreateContentView(): View {
        VehicleManager.initialize(context.applicationContext)
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        } else {
            setupTtsListener()
        }
        
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
        
        val activeBackendTag: android.widget.TextView? = overlayView.findViewById(R.id.activeBackendTag)
        if (activeBackendTag != null) {
            if (LocalLLMActivity.isCloudModelActive) {
                activeBackendTag.text = "Backend: Cloud"
            } else {
                activeBackendTag.text = "Backend: ${LLMManager.activeBackendString}"
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
            if (!isQueryProcessed) {
                android.util.Log.w("AssistantSession", "Ignoring mic trigger because query is still being processed.")
                return@setOnClickListener
            }
            LatencyLogger.reset()
            LatencyLogger.log("AssistantSession", "Voice Button Clicked")
            tts?.stop()
            btnMic.isEnabled = false
            LatencyLogger.log("AssistantSession", "Speech Recognizer startListening() called")
            
            // Removed explicit system beep muting so the user hears the native "listening" chime
            
            try {
                speechRecognizer?.startListening(speechRecognizerIntent)
            } catch (e: Exception) {
                LatencyLogger.log("AssistantSession", "Error starting speech recognizer: ${e.message}")
                stopDotAnimation("Error starting microphone.")
                statusText.visibility = View.VISIBLE
                voiceAnimation.state = VoiceAnimationView.State.IDLE
            }
            btnMic.isEnabled = true
        }
        
        // Update the active content view window with the newly inflated view
        setContentView(overlayView)
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // Removed explicit system beep unmuting
                
                LatencyLogger.log("AssistantSession", "Speech Recognizer onReadyForSpeech")
                statusText.visibility = View.VISIBLE
                startDotAnimation("")
                voiceAnimation.state = VoiceAnimationView.State.LISTENING
            }
            override fun onBeginningOfSpeech() {
                LatencyLogger.userSpeakingStartTimeMs = LatencyLogger.getTotalTime()
                LatencyLogger.log("AssistantSession", "Speech Recognizer onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Unmute just in case it was missed
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
                
                LatencyLogger.userSpeakingEndTimeMs = LatencyLogger.getTotalTime()
                LatencyLogger.log("AssistantSession", "Speech Recognizer onEndOfSpeech")
                startThinkingAnimation()
            }
            override fun onError(error: Int) {
                // Unmute just in case an error occurred before ready
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
                
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
                    kotlinx.coroutines.delay(2000)
                    if (statusText.text.toString() == errorMsg) {
                        statusText.text = ""
                        statusText.visibility = View.GONE
                        finish()
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                LatencyLogger.lastAsrTimeMs = LatencyLogger.getTotalTime()
                LatencyLogger.log("AssistantSession", "Speech Recognizer onResults")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                    val spokenText = matches[0]
                    etInput.setText(spokenText)
                    tts?.stop()
                    handleQuery(spokenText)
                } else {
                    stopDotAnimation("I didn't hear anything.")
                    statusText.visibility = View.VISIBLE
                    voiceAnimation.state = VoiceAnimationView.State.IDLE
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
        
        // Force window to occupy the entire screen on Automotive OS to prevent UI chopping
        window?.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        unloadJob?.cancel()
        
        setupSpeechRecognizer()
        
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
        
        if (LLMManager.engine == null || LLMManager.isPrewarming || LLMManager.isFirstMessage) {
            statusText.text = if (LLMManager.isPrewarming || LLMManager.isFirstMessage) "Pre-warming Model... This may take 20s" else "Initializing Model..."
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.GONE
            
            CoroutineScope(Dispatchers.Main).launch {
                // Wait for prewarm to finish if it's currently running
                while (LLMManager.isPrewarming) {
                    kotlinx.coroutines.delay(500)
                }
                
                LLMManager.autoInitialize(context, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        CoroutineScope(Dispatchers.Main).launch {
                            if (LLMManager.isFirstMessage) {
                                statusText.text = "Pre-warming Model... This may take 20s"
                                LLMManager.prewarm(context)
                            }
                            statusText.text = "Hi, how can I help you?"
                            inputControls.visibility = View.VISIBLE
                            btnSend.isEnabled = true
                            
                            // Automatically start listening if invoked via voice match/hotword
                            if (showFlags and SHOW_WITH_ASSIST != 0) {
                                kotlinx.coroutines.delay(500) // Wait for WakeWordService to release the mic
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
                    kotlinx.coroutines.delay(500) // Wait for WakeWordService to release the mic
                    btnMic.performClick()
                }
            }
        }
    }
    private var isQueryProcessed = true
    private var timeoutJob: kotlinx.coroutines.Job? = null

    private fun startThinkingAnimation() {
        CoroutineScope(Dispatchers.Main).launch {
            statusText.visibility = View.VISIBLE
            startDotAnimation("")
            voiceAnimation.state = VoiceAnimationView.State.THINKING
        }
    }

    private fun stopThinkingAnimation() {
        CoroutineScope(Dispatchers.Main).launch {
            voiceAnimation.state = VoiceAnimationView.State.IDLE
            stopDotAnimation()
        }
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
        if (LLMManager.isPrewarming) {
            Toast.makeText(context, "Model is prewarming, please wait a moment...", Toast.LENGTH_SHORT).show()
            stopThinkingAnimation()
            voiceAnimation.state = VoiceAnimationView.State.IDLE
            statusText.visibility = View.VISIBLE
            btnMic.isEnabled = true
            btnSend.isEnabled = true
            isQueryProcessed = true
            return
        }
        if (LLMManager.engine == null || LLMManager.conversation == null) return
        
        startThinkingAnimation()
        
        // Removed 'You:' and 'Assistant:' prefixes for cleaner UI
        responseText.text = ""
        currentHighlightStart = -1
        currentHighlightEnd = -1

        lastResponseBuilder.clear()
        targetDisplayMessage = ""
        currentDisplayLength = 0
        ttsSpokenLength = 0
        lastTtsUpdateTime = 0L
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
        val diningPref = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            prefs.getString("dining_pref", "Pure Vegetarian") ?: "Pure Vegetarian"
        }
        
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
        
        val expectFollowup = false
        // Push all heavy string building and IO off the main thread to prevent animation stuttering
        val finalPrompt: String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (isAgenticObservation) {
                MemoryManager.addTurn("System", interceptedQuery)
            } else {
                MemoryManager.addTurn("User", interceptedQuery)
            }
            val slidingHistory = MemoryManager.getSlidingWindowContext(500)
            
            // Anti-Hallucination Music Interceptor logic has been moved to the streaming loop
            // to allow the LLM to naturally generate a response.
            val qStr = interceptedQuery.lowercase()
            
            val isComplexQuery = interceptedQuery.length >= 25 || 
                    interceptedQuery.lowercase().matches(Regex("(?s).*\\b(temperat|hot|cold|navigat|direction|rout|diagnos).*"))
            
            // Keep the reminder extremely short to avoid overwhelming the 2B Edge LLM's attention mechanism
            val reminder = ""
            
            val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
            val dynamicState = SmartContextInjector.getInjectedContext(interceptedQuery, context)
            val vehicleState = if (dynamicState.isNotEmpty()) "[Current State: $dynamicState]" else ""
            
            val stateInject = if (vehicleState.isNotEmpty() && vehicleState != com.tcs.vehicleassistant.LLMManager.lastVehicleState) {
                com.tcs.vehicleassistant.LLMManager.lastVehicleState = vehicleState
                "$vehicleState\n"
            } else ""
            
            val currentToolsString = com.tcs.vehicleassistant.ToolManager.getLlmToolsPrompt(interceptedQuery, com.tcs.vehicleassistant.LLMManager.lastAiResponse)
            val toolsInject = if (currentToolsString.isNotBlank() && currentToolsString != com.tcs.vehicleassistant.LLMManager.lastInjectedTools) {
                com.tcs.vehicleassistant.LLMManager.lastInjectedTools = currentToolsString
                "\n[Available Tools]\n$currentToolsString\n"
            } else {
                ""
            }
            
            if (LocalLLMActivity.isCloudModelActive) {
                // Cloud API is stateless, so we MUST inject full history and state every time
                if (slidingHistory.isNotEmpty()) {
                    "$sysPrompt\n$reminder\n[Conversation History]\n$slidingHistory\n\n$vehicleState\n${if(currentToolsString.isNotBlank()) "\n[Available Tools]\n$currentToolsString\n" else ""}User: $interceptedQuery\nAssistant:"
                } else {
                    "$sysPrompt\n$reminder\n\n$vehicleState\n${if(currentToolsString.isNotBlank()) "\n[Available Tools]\n$currentToolsString\n" else ""}User: $interceptedQuery\nAssistant:"
                }
            } else {
                // Edge API natively maintains history in the KV Cache.
                // We ONLY pass the new delta to avoid O(n^2) context accumulation.
                if (LLMManager.isFirstMessage) {
                    LLMManager.isFirstMessage = false
                    // On the very first turn, the system prompt is already evaluated (Prewarm).
                    // We just pass the state, tools, and the query.
                    "$stateInject$toolsInject\n$interceptedQuery"
                } else {
                    "$stateInject$toolsInject\n$interceptedQuery"
                }
            }
        }


        val executedTools = mutableSetOf<String>()
        executedTools.addAll(previousExecutedTools)
        val toolFeedbacks = mutableListOf<String>()
        currentPendingTools.clear()
        val regex = Regex("(?i)<TOOL>\\s*([a-zA-Z0-9_]+)(?:\\((.*?)\\))?\\s*(?:</TOOL>|>)", RegexOption.DOT_MATCHES_ALL)
        val spokenTextLength = intArrayOf(0)
        val parsedSpokenLength = intArrayOf(0)
        
        val startTime = System.currentTimeMillis()
        var firstTokenTime = -1L

        val callback = object : MessageCallback, CloudMessageCallback {
                        var isHallucinating = false
                        var isDoneCalled = false
                        
                        override fun onMessage(message: Message) {
                            android.util.Log.i("AssistantSession", "onMessage called with Message: ${message.toString()}")
                            val contentsList = message.contents?.contents
                            android.util.Log.i("AssistantSession", "contentsList size: ${contentsList?.size}")
                            val textContent = contentsList?.firstOrNull() as? com.google.ai.edge.litertlm.Content.Text
                            if (textContent != null) {
                                android.util.Log.i("AssistantSession", "Extracted text: ${textContent.text}")
                                handleChunk(textContent.text)
                            } else {
                                android.util.Log.i("AssistantSession", "textContent was null or not Content.Text")
                            }
                        }
                        
                        override fun onMessage(chunkText: String) {
                            handleChunk(chunkText)
                        }

                        private fun handleChunk(chunkText: String) {
                            if (isHallucinating) return
                            
                            // Throttle the LLM inference thread to prevent CPU starvation of the TTS service
                            try {
                                if (firstTokenTime == -1L) {
                                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
                                }
                                Thread.sleep(15)
                            } catch (e: Exception) {}
                            
                            if (firstTokenTime == -1L) {
                                firstTokenTime = System.currentTimeMillis()
                                val ttft = firstTokenTime - startTime
                                LatencyLogger.log("AssistantSession", "Time to First Token (TTFT): ${ttft}ms")
                            }
                            
                            if (isQueryProcessed) return
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
                            
                            // Watchdog: Detect infinite runaway text or repetition loops
                            if (currentText.length > 250) {
                                val lastWords = currentText.trim().split(Regex("\\s+")).takeLast(5)
                                val isRepeating = lastWords.size == 5 && lastWords.distinct().size == 1
                                val isRunaway = currentText.length > 600 // A voice assistant should never output this much text natively
                                
                                if (isRepeating || isRunaway) {
                                    android.util.Log.e("AssistantSession", "LLM Loop/Runaway Detected. Force aborting output.")
                                    isHallucinating = true
                                    if (!isDoneCalled) {
                                        onDone()
                                        
                                        // Asynchronously reset the conversation to free up the C++ engine immediately
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                            com.tcs.vehicleassistant.LLMManager.resetConversation()
                                        }
                                    }
                                    return
                                }
                            }
                            
                            // Tool execution has been moved to onDone() to prevent streaming race conditions
                            // Hide any fully formed OR partially streamed tools from the TTS/UI to prevent index corruption
                            var displayMsg = currentText.replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>|$)"), "")
                            val lastTagIndex = displayMsg.lastIndexOf("<")
                            if (lastTagIndex != -1) {
                                val potentialTag = displayMsg.substring(lastTagIndex).uppercase()
                                if ("<TOOL".startsWith(potentialTag)) {
                                    displayMsg = displayMsg.substring(0, lastTagIndex)
                                }
                            }
                            displayMsg = displayMsg.trim()
                            
                            val displayStr = displayMsg.toString()
                            
                            // DISPATCH UI WORK TO MAIN THREAD
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                if (isQueryProcessed) return@launch
                                if (displayStr.isNotEmpty() && statusText.visibility == View.VISIBLE) {
                                    stopDotAnimation()
                                    voiceAnimation.state = VoiceAnimationView.State.SPEAKING
                                }
                                
                                targetDisplayMessage = displayStr
                                
                                // Typewriter Coroutine Buffer
                                if (typewriterJob == null || typewriterJob?.isActive != true) {
                                    typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                        while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                            val timeSinceTts = System.currentTimeMillis() - lastTtsUpdateTime
                                            val isTtsActive = lastTtsUpdateTime > 0L && timeSinceTts < 2000 // Active if TTS fired recently
                                            
                                            // Wait fluidly for TTS without causing stuttering
                                            if (isTtsActive && currentDisplayLength > ttsSpokenLength + 5) {
                                                kotlinx.coroutines.delay(16) // 1 frame wait (vsync aligned)
                                                continue
                                            }
                                            
                                            val step = 1
                                            val dynamicDelay = typingSpeedMs
                                            
                                            currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                            val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                            
                                            responseText.text = currentSubstring
                                            
                                            // Auto-scroll to bottom efficiently (batching to prevent message queue flooding)
                                            if (currentDisplayLength % 5 == 0) {
                                                svResponse?.post {
                                                    svResponse?.fullScroll(View.FOCUS_DOWN)
                                                }
                                            }
                                            
                                            kotlinx.coroutines.delay(dynamicDelay)
                                        }
                                    }
                                }
                            }
                            
                            // Streaming TTS Logic
                            val safeStartIndex = Math.min(spokenTextLength[0], displayMsg.length)
                            var remainingText = displayMsg.substring(safeStartIndex)
                            val sentenceRegex = "^(.*?)([.!?]{2,}(?:\\s+|$)|\\n|(?<=[a-zA-Z\\)\\]\\\"])[.,!?](?:\\s+|$))".toRegex()
                            var match = sentenceRegex.find(remainingText)
                            while (match != null) {
                                val sentence = match.value
                                spokenTextLength[0] += sentence.length
                                
                                val parsedSentence = parseMarkdown(sentence).toString()
                                val sentenceStartOffset = parsedSpokenLength[0]
                                parsedSpokenLength[0] += parsedSentence.length
                                
                                LatencyLogger.log("AssistantSession", "Sending sentence to TTS queue: SENTENCE_$sentenceStartOffset")
                                tts?.speak(parsedSentence, TextToSpeech.QUEUE_ADD, null, "SENTENCE_$sentenceStartOffset")
                                
                                remainingText = displayMsg.substring(spokenTextLength[0])
                                match = sentenceRegex.find(remainingText)
                            }
                    }

                    override fun onDone() {
                        if (isDoneCalled) return
                        isDoneCalled = true
                        
                        val totalTimeMs = System.currentTimeMillis() - startTime
                        val ttftMs = if (firstTokenTime > 0) firstTokenTime - startTime else totalTimeMs
                        val outputTimeMs = totalTimeMs - ttftMs
                        
                        val tempFinalMsg = lastResponseBuilder.toString()
                        val estimatedTokens = Math.max(1, (tempFinalMsg.length / 4.0).toInt())
                        val tps = if (outputTimeMs > 0) (estimatedTokens * 1000.0) / outputTimeMs else 0.0
                        
                        // Text generation is completely done
                        android.util.Log.i("AssistantSession", "RAW AI TEXT: ${tempFinalMsg}")
                        
                        LatencyLogger.log("AssistantSession", "Total Generation Time: ${totalTimeMs}ms (TTFT: ${ttftMs}ms, TPS: ${String.format("%.2f", tps)})")
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            
                            // Parse and execute tools on the FINAL text output
                            val matches = regex.findAll(tempFinalMsg)
                            for (match in matches) {
                                val toolName = match.groups[1]?.value?.trim() ?: continue
                                val toolArgs = match.groups[2]?.value?.trim() ?: ""
                                val toolCall = "${toolName}(${toolArgs})"
                                
                                val q = interceptedQuery.lowercase()
                                val isAffirmative = q.matches(Regex(".*\\b(yes|yeah|sure|do it|ok|yep|yup|go ahead|please)\\b.*"))
                                
                                val isGenericFood = q.matches(Regex("(?s).*\\b(hungry|food).*")) && 
                                                    !q.matches(Regex("(?s).*\\b(italian|mexican|pizza|burger|sushi|vegetarian|vegan|indian|thai|japanese).*"))
                                
                                val isDiag = q.matches(Regex("(?s).*\\b(wrong|broken|issue|light|code|door|diagnos|obd|engine|service).*"))
                                
                                if ((toolName == "navigate" || toolName == "search") && (isGenericFood || isDiag) && !isAffirmative) {
                                    android.util.Log.w("AssistantSession", "Intercepted hallucinatory tool call: $toolCall")
                                    continue
                                }
                                
                                if ((toolName == "turnOnCabinLight" || toolName == "turnOffCabinLight") && !q.matches(Regex("(?s).*\\b(light|cabin|interior|dark|see).*")) && !isAffirmative) {
                                    android.util.Log.w("AssistantSession", "Intercepted hallucinatory cabin light tool call: $toolCall")
                                    continue
                                }
                                
                                val isClimate = toolName.contains("Temperature", ignoreCase = true) || toolName.contains("Fan", ignoreCase = true) || toolName.endsWith("AC", ignoreCase = true)
                                val isClimateQuery = q.matches(Regex("(?s).*\\b(hot|cold|warm|cool|freeze|boil|a/c|ac|air|temp|climate|fan|heat|defrost|increas|incres|decreas|decres|driver|passenger|max|min|high|low).*"))
                                val isNumeric = q.matches(Regex(".*\\b\\d+\\b.*"))
                                
                                if (isClimate && !isClimateQuery && !isAffirmative && !isNumeric) {
                                    android.util.Log.w("AssistantSession", "Intercepted hallucinatory climate tool call: $toolCall")
                                    continue
                                }

                                val displayMsgWithoutTags = tempFinalMsg.replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>|$)"), "").trim()
                                val isAskingClarification = displayMsgWithoutTags.contains("?") && 
                                    (displayMsgWithoutTags.contains("What", ignoreCase = true) || 
                                     displayMsgWithoutTags.contains("Which", ignoreCase = true) || 
                                     displayMsgWithoutTags.contains("How much", ignoreCase = true) || 
                                     displayMsgWithoutTags.contains("Would you like", ignoreCase = true) || 
                                     displayMsgWithoutTags.contains("Are you sure", ignoreCase = true) ||
                                     displayMsgWithoutTags.contains("Do you have", ignoreCase = true)) ||
                                     displayMsgWithoutTags.contains("clarify", ignoreCase = true) ||
                                     displayMsgWithoutTags.contains("specify", ignoreCase = true)
                                
                                if (isAskingClarification) {
                                    val isClimateTool = toolName.contains("Temperature", ignoreCase = true) || 
                                                        toolName.contains("Fan", ignoreCase = true) ||
                                                        toolName.contains("AC", ignoreCase = true)
                                    val isLiteralPlaceholder = toolArgs == "SONG" || toolArgs == "VAL" || toolArgs == "direction" || toolArgs == "LEVEL"
                                    
                                    if (isClimateTool || isLiteralPlaceholder) {
                                        android.util.Log.w("AssistantSession", "Intercepted desynchronized tool call during a clarifying question: $toolCall")
                                        continue
                                    }
                                }

                                var correctedToolCall = toolCall
                                
                                // Fix Edge LLM failing to substitute SONG placeholder
                                if (toolName == "playMusic" && (toolArgs.equals("SONG", ignoreCase = true) || toolArgs.equals("music", ignoreCase = true))) {
                                    val cleanedQuery = interceptedQuery.replace(Regex("(?i)\\b(play|music|some|by)\\b"), "").trim()
                                    if (cleanedQuery.isNotEmpty()) {
                                        android.util.Log.w("AssistantSession", "Correcting hallucinated literal SONG to: $cleanedQuery")
                                        correctedToolCall = "playMusic($cleanedQuery)"
                                    }
                                }
                                
                                if (toolName.contains("Passenger", ignoreCase = true) || toolName.contains("Driver", ignoreCase = true) || toolName.contains("Temperature", ignoreCase = true)) {
                                    val argsStr = toolCall.substringAfter("(").substringBefore(")")
                                    val toolVal = argsStr.toDoubleOrNull()
                                    if (toolVal != null && toolVal < 15.0) {
                                        val matchTemp = Regex("\\b(\\d{2})\\b").findAll(displayMsgWithoutTags).lastOrNull() ?: Regex("\\b(\\d{2})\\b").findAll(interceptedQuery).lastOrNull()
                                        if (matchTemp != null) {
                                            val realNum = matchTemp.groupValues[1]
                                            android.util.Log.w("AssistantSession", "Correcting truncated temperature $toolVal to $realNum")
                                            correctedToolCall = toolCall.replace("($argsStr)", "($realNum)")
                                        } else {
                                            val zone = if (toolName.contains("passenger", true)) "passenger" else "driver"
                                            val currentTemp = com.tcs.vehicleassistant.VehicleManager.getRealTemperature(zone).toDouble()
                                            val isDecrease = interceptedQuery.contains("decrease", true) || interceptedQuery.contains("lower", true) || interceptedQuery.contains("cold", true) || interceptedQuery.contains("cool", true)
                                            val isIncrease = interceptedQuery.contains("increase", true) || interceptedQuery.contains("raise", true) || interceptedQuery.contains("hot", true) || interceptedQuery.contains("warm", true)
                                            
                                            if (isDecrease) {
                                                val newTemp = Math.round(currentTemp - 2.0).toString()
                                                correctedToolCall = toolCall.replace("($argsStr)", "($newTemp)")
                                            } else if (isIncrease) {
                                                val newTemp = Math.round(currentTemp + 2.0).toString()
                                                correctedToolCall = toolCall.replace("($argsStr)", "($newTemp)")
                                            }
                                        }
                                    }
                                }
                                
                                if (executedTools.add(correctedToolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $correctedToolCall")
                                    val toolDef = ToolManager.getToolDefinition(correctedToolCall)
                                    if (toolDef?.requiresConfirmation == true) {
                                        pendingConfirmationTool = correctedToolCall
                                        // Wait, we can't change the spoken response here as TTS already queued it!
                                        // BUT confirmation tools are extremely rare and we can just let it finish.
                                    } else {
                                        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { kotlinx.coroutines.withTimeoutOrNull(10000L) {
                                            executeToolCall(correctedToolCall) } ?: "System Error: Tool execution timed out."
                                        }
                                        currentPendingTools.add(job)
                                    }
                                }
                            }
                            
                            if (currentPendingTools.isNotEmpty()) {
                                setKeepAwake(true)
                                // Keep the thinking animation going during tool execution and recursive loops
                                startThinkingAnimation()
                                val feedbacks = kotlinx.coroutines.awaitAll(*currentPendingTools.toTypedArray()).filterNotNull()
                                toolFeedbacks.addAll(feedbacks)
                                setKeepAwake(false)
                            }
                            
                            android.util.Log.i("LLMMetrics", "================ LLM GENERATION METRICS ================")
                            android.util.Log.i("LLMMetrics", "Model Backend:              ${if (LocalLLMActivity.isCloudModelActive) "Cloud LLM API" else "Local Edge (Google MediaPipe LiteRT)"}")
                            if (LatencyLogger.lastAsrTimeMs > 0) {
                                if (LatencyLogger.userSpeakingStartTimeMs > 0 && LatencyLogger.userSpeakingEndTimeMs > 0) {
                                    val reactionTime = LatencyLogger.userSpeakingStartTimeMs
                                    val speakingDuration = LatencyLogger.userSpeakingEndTimeMs - LatencyLogger.userSpeakingStartTimeMs
                                    val asrProcessingTime = LatencyLogger.lastAsrTimeMs - LatencyLogger.userSpeakingEndTimeMs
                                    android.util.Log.i("LLMMetrics", "Button Tap to Speech Start: ${reactionTime}ms")
                                    android.util.Log.i("LLMMetrics", "User Speaking Duration:     ${speakingDuration}ms")
                                    android.util.Log.i("LLMMetrics", "ASR Processing Time:        ${asrProcessingTime}ms")
                                } else {
                                    android.util.Log.i("LLMMetrics", "ASR (Speech-to-Text) Time:  ${LatencyLogger.lastAsrTimeMs}ms")
                                }
                            }
                            android.util.Log.i("LLMMetrics", "TTFT (First Token):         ${ttftMs}ms")
                            if (LatencyLogger.lastToolTimeMs > 0) {
                                android.util.Log.i("LLMMetrics", "Tool Execution Time:        ${LatencyLogger.lastToolTimeMs}ms")
                            }
                            android.util.Log.i("LLMMetrics", "Total Generation Time:      ${totalTimeMs}ms")
                            android.util.Log.i("LLMMetrics", "Estimated Output Tokens:    ~${estimatedTokens} tokens")
                            android.util.Log.i("LLMMetrics", "Output Speed (TPS):         ${String.format("%.2f", tps)} tokens/sec")
                            android.util.Log.i("LLMMetrics", "Overall Session Time:       ${LatencyLogger.getTotalTime()}ms")
                            android.util.Log.i("LLMMetrics", "========================================================")
                            
                            if (currentPendingTools.isNotEmpty()) {
                                val isAgenticLoopEnabled = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE).getBoolean("agentic_loop_enabled", true)
                                
                                val rawResponse = lastResponseBuilder.toString()
                                val responseWithoutTags = rawResponse.replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>|$)"), "").trim()
                                val hasConversationalText = responseWithoutTags.length > 5
                                val hasError = toolFeedbacks.any { it.contains("Error", true) || it.contains("Failed", true) || it.contains("couldn't", true) }
                                
                                // Smart Bypass: Skip the Agentic Loop if the AI already generated conversational text AND the tool succeeded.
                                // This provides instant responses for terminal action tools (e.g. navigate, set_temp) without stalling the GPU.
                                // However, if the tool is a data-gathering query (search, check, read, get, etc.), ALWAYS run the loop so the AI can summarize the results!
                                val isQueryTool = executedTools.any { it.contains("search", ignoreCase = true) || it.contains("check", ignoreCase = true) || it.contains("get", ignoreCase = true) || it.contains("diagnos", ignoreCase = true) || it.contains("read", ignoreCase = true) || it.contains("find", ignoreCase = true) || it.contains("recommend", ignoreCase = true) }
                                val isTerminalTool = executedTools.isNotEmpty() && !isQueryTool
                                val requiresAgenticLoop = executedTools.any { com.tcs.vehicleassistant.ToolManager.getToolDefinition(it)?.requiresAgenticLoop == true }
                                val shouldRunAgenticLoop = isAgenticLoopEnabled && loopCount < 3 && (hasError || isQueryTool || requiresAgenticLoop || (!hasConversationalText && !isTerminalTool))
                                
                                if (shouldRunAgenticLoop) {
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
                            
                            // Removed broken Auto-Context Clearing Hack. The AI outputting only a tool tag is a SUCCESS, not a KV cache overflow.
                            
                            MemoryManager.addTurn("Assistant", finalMsg.trim())
                            
                            finalMsg = finalMsg.replace(Regex("(?i)<TOOL>[\\s\\S]*?(</TOOL>|$)"), "")
                            val finalLastTagIndex = finalMsg.lastIndexOf("<")
                            if (finalLastTagIndex != -1) {
                                val potentialTag = finalMsg.substring(finalLastTagIndex).uppercase()
                                if ("<TOOL".startsWith(potentialTag)) {
                                    finalMsg = finalMsg.substring(0, finalLastTagIndex)
                                }
                            }
                            finalMsg = finalMsg.trim()
                            val isQuestionBeforeFeedback = expectFollowup || 
                                             finalMsg.trim().endsWith("?") || 
                                             finalMsg.contains("would you like", ignoreCase = true) || 
                                             finalMsg.contains("if you'd like", ignoreCase = true) || 
                                             finalMsg.contains("do you want", ignoreCase = true) || 
                                             finalMsg.contains("shall i", ignoreCase = true)
                            
                            if (finalMsg.isEmpty()) {
                                if (toolFeedbacks.isNotEmpty()) {
                                    finalMsg = toolFeedbacks.joinToString("\n")
                                } else {
                                    finalMsg = "Executing command..."
                                }
                            } else if (toolFeedbacks.isNotEmpty()) {
                                val hasError = toolFeedbacks.any { it.contains("Error", true) || it.contains("Failed", true) || it.contains("couldn't", true) }
                                if (hasError || !isQuestionBeforeFeedback) {
                                    finalMsg += "\n\n" + toolFeedbacks.joinToString("\n")
                                }
                            }
                            
                            targetDisplayMessage = finalMsg
                            LLMManager.lastAiResponse = finalMsg
                            if (typewriterJob == null || typewriterJob?.isActive != true) {
                                typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(400) // Wait for TTS engine to initialize audio buffer
                                    while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                                        val timeSinceTts = System.currentTimeMillis() - lastTtsUpdateTime
                                        val isTtsActive = lastTtsUpdateTime > 0L && timeSinceTts < 2000 // Active if TTS fired recently
                                        
                                        if (isTtsActive && currentDisplayLength > ttsSpokenLength + 5) {
                                            kotlinx.coroutines.delay(16) // 1 frame wait
                                            continue
                                        }
                                        
                                        val step = 1
                                        val dynamicDelay = typingSpeedMs
                                        currentDisplayLength = Math.min(currentDisplayLength + step, targetDisplayMessage.length)
                                        val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                                        
                                        responseText.text = currentSubstring
                                        if (currentDisplayLength % 5 == 0) {
                                            svResponse?.post { svResponse?.fullScroll(View.FOCUS_DOWN) }
                                        }
                                        kotlinx.coroutines.delay(dynamicDelay)
                                    }
                                    // Apply Markdown formatting only once after typewriter finishes
                                    responseText.text = parseMarkdown(targetDisplayMessage)
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
                                                 finalMsg.contains("?") || 
                                                 finalMsg.contains("would you like", ignoreCase = true) || 
                                                 finalMsg.contains("if you'd like", ignoreCase = true) || 
                                                 finalMsg.contains("do you want", ignoreCase = true) || 
                                                 finalMsg.contains("shall i", ignoreCase = true) ||
                                                 finalMsg.contains("could you", ignoreCase = true)
                                                 
                                val finalUtterance = if (isQuestion) "QUESTION_FINAL" else if (toolFeedbacks.isNotEmpty() || currentPendingTools.isNotEmpty()) "STATEMENT_FINAL_TOOL" else "STATEMENT_FINAL"
                                tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, finalUtterance)
                            } else {
                                val isQuestion = expectFollowup || 
                                                 finalMsg.contains("?") || 
                                                 finalMsg.contains("would you like", ignoreCase = true) || 
                                                 finalMsg.contains("if you'd like", ignoreCase = true) || 
                                                 finalMsg.contains("do you want", ignoreCase = true) ||
                                                 finalMsg.contains("shall i", ignoreCase = true) ||
                                                 finalMsg.contains("could you", ignoreCase = true)
                                                 
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
                        if (isDoneCalled) return
                        isDoneCalled = true
                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            android.util.Log.e("AssistantSession", "LLM Generation Error", throwable)
                            
                            MemoryManager.clearMemory()
                            
                            if (retryCount < 1) {
                                android.util.Log.i("AssistantSession", "Automatic engine restart triggered. Re-initializing...")
                                stopThinkingAnimation()
                                responseText.text = "Hardware reset..."
                                LLMManager.autoInitialize(context, force = true, callback = object : com.tcs.vehicleassistant.LLMManager.InitCallback {
                                    override fun onSuccess() {
                                        android.util.Log.i("AssistantSession", "Automatic recovery successful. Retrying query...")
                                        CoroutineScope(Dispatchers.Main).launch {
                                            responseText.text = "Recovered. Processing..."
                                            processQuery(query, retryCount + 1, loopCount, isAgenticObservation, previousExecutedTools)
                                        }
                                    }
                                    override fun onError(e: Exception) {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            responseText.text = "Hardware Recovery Failed."
                                            statusText.visibility = View.VISIBLE
                                            statusText.text = "Error"
                                            btnSend.isEnabled = true
                                            btnMic.isEnabled = true
                                            isQueryProcessed = true
                                        }
                                    }
                                })
                            } else {
                                stopThinkingAnimation()
                                if (throwable.message?.contains("Cancellation") == true) return@launch
                                val err = "Model Inference Failed: ${throwable.message}"
                                responseText.text = err
                                statusText.visibility = View.VISIBLE
                                statusText.text = "Error"
                                btnSend.isEnabled = true
                                btnMic.isEnabled = true
                                isQueryProcessed = true
                            }
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
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        while (LLMManager.isPrewarming) {
                            kotlinx.coroutines.delay(100)
                        }
                        LLMManager.conversation!!.sendMessageAsync(
                            Contents.of(Content.Text(finalPrompt)),
                            callback,
                            emptyMap()
                        )
                    } catch (e: Exception) {
                        CoroutineScope(Dispatchers.Main).launch {
                            statusText.text = "Error"
                            stopThinkingAnimation()
                            responseText.text = "Failed to communicate with NPU."
                            btnSend.isEnabled = true
                        }
                    }
                }
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
        speechRecognizer?.destroy()
        dotAnimatorJob?.cancel()
        
        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)
        
        super.onDestroy()
    }
}

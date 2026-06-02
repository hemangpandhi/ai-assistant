package com.example.gemininano

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
    private lateinit var svResponse: android.widget.ScrollView
    
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var dotAnimatorJob: kotlinx.coroutines.Job? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    CoroutineScope(Dispatchers.Main).launch {
                        if (utteranceId == "QUESTION_FINAL") {
                            btnMic.performClick()
                        } else if (utteranceId == "STATEMENT_FINAL") {
                            kotlinx.coroutines.delay(500)
                            finish()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    CoroutineScope(Dispatchers.Main).launch {
                        finish()
                    }
                }
            })
        }
    }

    override fun onCreateContentView(): View {
        VehicleManager.initialize(context.applicationContext)
        tts = TextToSpeech(context, this)
        overlayView = layoutInflater.inflate(R.layout.assistant_overlay, null)
        
        statusText = overlayView.findViewById(R.id.assistantStatusText)
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)
        btnMic = overlayView.findViewById(R.id.btnMic)
        btnOpenApp = overlayView.findViewById(R.id.btnOpenApp)
        svResponse = overlayView.findViewById(R.id.svResponse)
        inputControls = overlayView.findViewById(R.id.inputControlsContainer)
        voiceAnimation = overlayView.findViewById(R.id.voiceAnimation)
        
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
                handleQuery(query)
                etInput.setText("")
            }
        }
        
        setupSpeechRecognizer()

        return overlayView
    }
    
    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.visibility = View.VISIBLE
                startDotAnimation("Listening")
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
                if (!matches.isNullOrEmpty()) {
                    etInput.setText(matches[0])
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

        btnMic.setOnClickListener {
            speechRecognizer?.startListening(speechRecognizerIntent)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        statusText.visibility = View.VISIBLE
        stopDotAnimation("Hi, how can I help you?")
        responseText.text = ""
        etInput.setText("")
        voiceAnimation.state = VoiceAnimationView.State.IDLE
        
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
                            btnMic.performClick()
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
                btnMic.performClick()
            }
        }
    }

    private var isQueryProcessed = false
    private var timeoutJob: kotlinx.coroutines.Job? = null

    private fun startThinkingAnimation() {
        statusText.visibility = View.VISIBLE
        startDotAnimation("Processing")
        voiceAnimation.state = VoiceAnimationView.State.THINKING
    }

    private fun stopThinkingAnimation() {
        voiceAnimation.state = VoiceAnimationView.State.IDLE
        stopDotAnimation()
    }

    private fun executeToolCall(toolCall: String): String? {
        return ToolManager.executeToolCall(context, toolCall) { intent ->
            try {
                startVoiceActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("AssistantSession", "startVoiceActivity failed", e)
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    android.util.Log.e("AssistantSession", "Fallback startActivity failed", e2)
                }
            }
        }
    }

    private fun handleQuery(query: String) {
        if (LLMManager.engine == null || LLMManager.conversation == null) return
        
        startThinkingAnimation()
        
        // Removed 'You:' and 'Assistant:' prefixes for cleaner UI
        responseText.text = ""

        lastResponseBuilder.clear()
        btnSend.isEnabled = false
        isQueryProcessed = false
        
        processQuery(query)
    }
    
    private fun processQuery(query: String) {
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
        
        val finalPrompt: String
        if (LLMManager.isFirstMessage) {
            val sysPrompt = LLMManager.getSystemPrompt(context, query)
            finalPrompt = "$sysPrompt\n\nUser: $query"
            LLMManager.isFirstMessage = false
        } else {
            val reminder = "\n(Reminder: Use exact <TOOL> XML tags for car actions.)"
            finalPrompt = "[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder\nUser: $query"
        }

        val executedTools = mutableSetOf<String>()
        val toolFeedbacks = mutableListOf<String>()
        val regex = "(?i)<TOOL>(.*?)</TOOL>".toRegex()
        val spokenTextLength = intArrayOf(0)
        
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
                                if (executedTools.add(toolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $toolCall")
                                    val feedback = executeToolCall(toolCall)
                                    if (feedback != null) toolFeedbacks.add(feedback)
                                }
                            }
                            
                            var displayMsg = currentText.replace(regex, "").trim()
                            
                            
                            if (displayMsg.isNotEmpty() && statusText.visibility == View.VISIBLE) {
                                statusText.visibility = View.GONE
                                stopDotAnimation()
                            }
                            
                            responseText.text = parseMarkdown(displayMsg)
                            
                            // Auto-scroll to bottom as text streams
                            svResponse.post {
                                svResponse.fullScroll(View.FOCUS_DOWN)
                            }
                            
                            // Streaming TTS Logic
                            val safeStartIndex = Math.min(spokenTextLength[0], displayMsg.length)
                            var remainingText = displayMsg.substring(safeStartIndex)
                            val sentenceRegex = "^(.*?)([.!?]+(?:\\s+|$)|\\n)".toRegex()
                            var match = sentenceRegex.find(remainingText)
                            while (match != null) {
                                val sentence = match.value
                                spokenTextLength[0] += sentence.length
                                tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, "PARTIAL")
                                
                                remainingText = displayMsg.substring(spokenTextLength[0])
                                match = sentenceRegex.find(remainingText)
                            }
                        }
                    }

                    override fun onDone() {
                        val totalTime = System.currentTimeMillis() - startTime
                        android.util.Log.i("LLMLatency", "[AssistantSession] Total Generation Time: ${totalTime}ms")
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            timeoutJob?.cancel()
                            var finalMsg = lastResponseBuilder.toString()
                            
                            // Auto-Context Clearing Hack for silent KV Cache overflows
                            if (finalMsg.trim().length <= 3) {
                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Resetting...")
                                LLMManager.resetConversation()
                                handleQuery(query)
                                return@launch
                            }
                            
                            finalMsg = finalMsg.replace(regex, "").trim()
                            if (finalMsg.isEmpty() && toolFeedbacks.isNotEmpty()) {
                                finalMsg = toolFeedbacks.joinToString("\n")
                            } else if (toolFeedbacks.isNotEmpty()) {
                                val guardrailMessages = toolFeedbacks.filter { it.startsWith("Safety Warning:") }
                                if (guardrailMessages.isNotEmpty()) {
                                    finalMsg += "\n\n" + guardrailMessages.joinToString("\n")
                                }
                            }
                            
                            
                            responseText.text = parseMarkdown(finalMsg)
                            
                            // Final auto-scroll
                            svResponse.post {
                                svResponse.fullScroll(View.FOCUS_DOWN)
                            }
                            
                            stopThinkingAnimation()
                            btnSend.isEnabled = true
                            isQueryProcessed = true
                            
                            if (finalMsg.isNotBlank()) {
                                val safeIndex = Math.min(spokenTextLength[0], finalMsg.length)
                                val remainingSentence = finalMsg.substring(safeIndex).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    tts?.speak(remainingSentence, TextToSpeech.QUEUE_ADD, null, "PARTIAL")
                                }
                                val finalUtterance = if (finalMsg.trim().endsWith("?")) "QUESTION_FINAL" else "STATEMENT_FINAL"
                                tts?.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, finalUtterance)
                            } else {
                                if (finalMsg.trim().endsWith("?")) {
                                    btnMic.performClick()
                                } else {
                                    kotlinx.coroutines.delay(2000)
                                    finish()
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
                    val systemPrompt = LLMManager.getSystemPrompt(context, query)
                    if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                        GeminiManager.sendMessageAsync(systemPrompt, query, callback)
                    } else {
                        AnthropicManager.sendMessageAsync(systemPrompt, query, callback)
                    }
                }
            } else {
                LLMManager.conversation!!.sendMessageAsync(
                    Contents.of(Content.Text(finalPrompt)),
                    callback,
                    emptyMap()
                )
            }
        } catch (e: Exception) {     } catch (e: Exception) {
            statusText.text = "Error"
            stopThinkingAnimation()
            responseText.text = "An unexpected error occurred."
            btnSend.isEnabled = true
        }
    }

    private fun startDotAnimation(baseText: String) {
        dotAnimatorJob?.cancel()
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
        super.onDestroy()
    }
}

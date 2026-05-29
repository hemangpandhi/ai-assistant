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
    private lateinit var ivCenterMic: View
    private lateinit var svResponse: android.widget.ScrollView
    
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var idleAnimator: android.animation.ObjectAnimator? = null
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
        VehicleManager.initialize(context)
        tts = TextToSpeech(context, this)
        overlayView = layoutInflater.inflate(R.layout.assistant_overlay, null)
        
        statusText = overlayView.findViewById(R.id.assistantStatusText)
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)
        btnMic = overlayView.findViewById(R.id.btnMic)
        btnOpenApp = overlayView.findViewById(R.id.btnOpenApp)
        voiceAnimation = overlayView.findViewById(R.id.voiceAnimation)
        ivCenterMic = overlayView.findViewById(R.id.ivCenterMic)
        svResponse = overlayView.findViewById(R.id.svResponse)
        inputControls = overlayView.findViewById(R.id.inputControlsContainer)
        
        idleAnimator = android.animation.ObjectAnimator.ofFloat(ivCenterMic, "alpha", 1f, 0.3f, 1f).apply {
            duration = 2000
            repeatCount = android.animation.ValueAnimator.INFINITE
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
                ivCenterMic.visibility = View.VISIBLE
                idleAnimator?.cancel()
                ivCenterMic.alpha = 1f
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
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout."
                    13 -> "Offline Language Pack Missing! Type instead."
                    else -> "Voice Error: $error"
                }
                stopDotAnimation(errorMsg)
                statusText.visibility = View.VISIBLE
                voiceAnimation.state = VoiceAnimationView.State.IDLE
                ivCenterMic.visibility = View.VISIBLE
                idleAnimator?.start()
                
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
        ivCenterMic.visibility = View.VISIBLE
        idleAnimator?.start()
        
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
        ivCenterMic.visibility = View.GONE
        idleAnimator?.cancel()
    }

    private fun stopThinkingAnimation() {
        voiceAnimation.state = VoiceAnimationView.State.IDLE
        ivCenterMic.visibility = View.VISIBLE
        idleAnimator?.start()
        stopDotAnimation()
    }

    private fun executeToolCall(toolCall: String) {
        try {
            if (toolCall.startsWith("increaseTemperature")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 1.0
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                VehicleManager.writeTemperatureToVhal((currentTemp + value).toFloat())
            } else if (toolCall.startsWith("decreaseTemperature")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 1.0
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                VehicleManager.writeTemperatureToVhal((currentTemp - value).toFloat())
            } else if (toolCall.startsWith("setTemperature")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                VehicleManager.writeTemperatureToVhal(value.toFloat())
            } else if (toolCall.startsWith("turnOnDefroster")) {
                VehicleManager.writeDefrosterToVhal(true)
            } else if (toolCall.startsWith("turnOffDefroster")) {
                VehicleManager.writeDefrosterToVhal(false)
            } else if (toolCall.startsWith("setSeatHeater")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 1
                VehicleManager.writeSeatHeaterToVhal(value)
            } else if (toolCall.startsWith("setSeatMassager")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 1
                VehicleManager.writeSeatMassagerToVhal(value)
            } else if (toolCall.startsWith("setWindowPosition")) {
                if (VehicleManager.getRealSpeed() > 70) {
                    android.util.Log.w("SafetyGuardrail", "Speed > 70mph. Ignored setWindowPosition tool.")
                } else {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 50
                    VehicleManager.writeWindowPositionToVhal(value)
                }
            } else if (toolCall.startsWith("navigate")) {
                val dest = toolCall.substringAfter("(").substringBefore(")")
                
                // Show a toast to guarantee visual confirmation to the user
                android.widget.Toast.makeText(context, "Navigating to: $dest", android.widget.Toast.LENGTH_SHORT).show()
                
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${android.net.Uri.encode(dest)}"))
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(dest)}"))
                    fallbackIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        context.startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(dest)}"))
                        browserIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        try {
                            context.startActivity(browserIntent)
                        } catch (e3: Exception) {
                            android.util.Log.e("Navigation", "Failed to launch any navigation intents", e3)
                        }
                    }
                }
            } else if (toolCall.startsWith("playMusic")) {
                val query = toolCall.substringAfter("(").substringBefore(")")
                val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                intent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                intent.putExtra(android.app.SearchManager.QUERY, query)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
            } else if (toolCall.startsWith("call")) {
                val contact = toolCall.substringAfter("(").substringBefore(")")
                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${android.net.Uri.encode(contact)}"))
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } else if (toolCall.startsWith("remember")) {
                val fact = toolCall.substringAfter("(").substringBefore(")")
                val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val currentMemory = prefs.getString("user_memory", "") ?: ""
                val newMemory = if (currentMemory.isEmpty()) fact else "$currentMemory. $fact"
                prefs.edit().putString("user_memory", newMemory).apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("AssistantSession", "Failed to parse tool call: $toolCall", e)
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
        
        if (LLMManager.isWarmingUp) {
            statusText.text = "Warming up AI context... please wait a moment."
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                while (LLMManager.isWarmingUp) {
                    kotlinx.coroutines.delay(500)
                }
                statusText.visibility = View.GONE
                processQuery(query)
            }
        } else {
            processQuery(query)
        }
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
        val finalPrompt = if (LLMManager.isFirstMessage) {
            LLMManager.isFirstMessage = false
            LLMManager.getSystemPrompt(context) + "\nUser: " + query
        } else {
            "[Current State: Temp ${VehicleManager.getRealTemperature()}F, Speed ${VehicleManager.getRealSpeed()}mph, Heater ${VehicleManager.getRealSeatHeaterLevel()}]\nUser: " + query
        }

        val executedTools = mutableSetOf<String>()
        val regex = "(?i)<TOOL>(.*?)</TOOL>".toRegex()
        val spokenTextLength = intArrayOf(0)
        
        val startTime = System.currentTimeMillis()
        var firstTokenTime = -1L

        try {
            LLMManager.conversation!!.sendMessageAsync(
                Contents.of(Content.Text(finalPrompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (firstTokenTime == -1L) {
                            firstTokenTime = System.currentTimeMillis()
                            val ttft = firstTokenTime - startTime
                            android.util.Log.i("LLMLatency", "[AssistantSession] Time to First Token (TTFT): ${ttft}ms")
                        }
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            voiceAnimation.state = VoiceAnimationView.State.SPEAKING
                            ivCenterMic.visibility = View.GONE
                            val chunk = message.toString()
                            lastResponseBuilder.append(chunk)
                            val currentText = lastResponseBuilder.toString()
                            
                            // Handle Tool Calls (e.g., <TOOL>increaseTemperature(2)</TOOL>)
                            val matches = regex.findAll(currentText)
                            for (match in matches) {
                                val toolCall = match.groups[1]?.value?.trim() ?: continue
                                if (executedTools.add(toolCall)) {
                                    android.util.Log.d("AssistantSession", "Executing tool from LLM: $toolCall")
                                    executeToolCall(toolCall)
                                }
                            }
                            
                            val displayMsg = currentText.replace(regex, "").trim()
                            
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
                            var remainingText = displayMsg.substring(spokenTextLength[0])
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
                            if (finalMsg.isEmpty() && executedTools.isNotEmpty()) {
                                finalMsg = executedTools.joinToString("\n") { tool ->
                                    when {
                                        tool.startsWith("increaseTemperature") -> "I've increased the temperature by ${tool.substringAfter("(").substringBefore(")")} degrees."
                                        tool.startsWith("decreaseTemperature") -> "I've decreased the temperature by ${tool.substringAfter("(").substringBefore(")")} degrees."
                                        tool.startsWith("setTemperature") -> "I've set the temperature to ${tool.substringAfter("(").substringBefore(")")} degrees."
                                        tool.startsWith("setSeatHeater") -> "I've adjusted the seat heater."
                                        tool.startsWith("setSeatMassager") -> "I've turned on the seat massager for you."
                                        tool.startsWith("turnOnDefroster") -> "I've turned on the defroster."
                                        tool.startsWith("turnOffDefroster") -> "I've turned off the defroster."
                                        tool.startsWith("setWindowPosition") -> "I've adjusted the windows."
                                        tool.startsWith("navigate") -> "Routing to ${tool.substringAfter("(").substringBefore(")")}."
                                        tool.startsWith("playMusic") -> "Playing ${tool.substringAfter("(").substringBefore(")")}."
                                        tool.startsWith("call") -> "Calling ${tool.substringAfter("(").substringBefore(")")}."
                                        tool.startsWith("remember") -> "Got it, I've remembered that."
                                        else -> "Action completed."
                                    }
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
                                val remainingSentence = finalMsg.substring(spokenTextLength[0]).trim()
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
                            statusText.text = "Error"
                            stopThinkingAnimation()
                            responseText.text = throwable.message ?: "An unexpected error occurred."
                            btnSend.isEnabled = true
                            LLMManager.isFirstMessage = true
                            isQueryProcessed = true
                            
                            kotlinx.coroutines.delay(2000)
                            finish()
                        }
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
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
        idleAnimator?.cancel()
        dotAnimatorJob?.cancel()
        super.onDestroy()
    }
}

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
    
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    CoroutineScope(Dispatchers.Main).launch {
                        if (utteranceId == "QUESTION") {
                            btnMic.performClick()
                        } else {
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
        inputControls = etInput.parent as View
        
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Listening..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Processing..."
            }
            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK -> "Network Error (No Internet/Language Pack)."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network Timeout."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout."
                    else -> "Voice Error: $error"
                }
                statusText.text = errorMsg
                
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
            LLMManager.resetConversation()
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

    private fun handleQuery(query: String) {
        if (LLMManager.engine == null || LLMManager.conversation == null) return
        
        statusText.text = "Thinking..."
        responseText.text = android.text.Html.fromHtml("<b>You:</b> $query<br><br><b>Assistant:</b> ", android.text.Html.FROM_HTML_MODE_LEGACY)
        lastResponseBuilder.clear()
        btnSend.isEnabled = false
        isQueryProcessed = false
        
        // Timeout watchdog
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.delay(30000) // 30 sec timeout
            if (!isQueryProcessed) {
                statusText.text = "Timeout - Restarting Model..."
                responseText.text = "Please wait a moment."
                btnSend.isEnabled = false
                LLMManager.autoInitialize(context, force = true, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        statusText.text = "Hi, how can I help you?"
                        responseText.text = ""
                        btnSend.isEnabled = true
                    }
                    override fun onError(e: Exception) {
                        statusText.text = "Error restarting."
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
            query
        }

        try {
            LLMManager.conversation!!.sendMessageAsync(
                Contents.of(Content.Text(finalPrompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val chunk = message.toString()
                            lastResponseBuilder.append(chunk)
                            responseText.text = android.text.Html.fromHtml("<b>You:</b> $query<br><br><b>Assistant:</b> " + lastResponseBuilder.toString().replace("\n", "<br>"), android.text.Html.FROM_HTML_MODE_LEGACY)
                        }
                    }

                    override fun onDone() {
                        CoroutineScope(Dispatchers.Main).launch {
                            var finalMsg = lastResponseBuilder.toString()
                            
                            // Auto-Context Clearing Hack for silent KV Cache overflows
                            if (finalMsg.trim().length <= 3) {
                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Resetting...")
                                LLMManager.resetConversation()
                                handleQuery(query)
                                return@launch
                            }
                            
                            // Regex parser for inline tool tags
                            val regex = "<TOOL>(.*?)</TOOL>".toRegex()
                            val matches = regex.findAll(finalMsg)
                            for (match in matches) {
                                val toolCall = match.groups[1]?.value ?: continue
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
                                    } else if (toolCall.startsWith("setWindowPosition")) {
                                        if (VehicleManager.getRealSpeed() > 70) {
                                            android.util.Log.w("SafetyGuardrail", "Speed > 70mph. Ignored setWindowPosition tool.")
                                        } else {
                                            val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 50
                                            VehicleManager.writeWindowPositionToVhal(value)
                                        }
                                    } else if (toolCall.startsWith("navigate")) {
                                        val dest = toolCall.substringAfter("(").substringBefore(")")
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(dest)}"))
                                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        context.startActivity(intent)
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
                            
                            finalMsg = finalMsg.replace(regex, "").trim()
                            responseText.text = android.text.Html.fromHtml("<b>You:</b> $query<br><br><b>Assistant:</b> " + finalMsg.replace("\n", "<br>"), android.text.Html.FROM_HTML_MODE_LEGACY)
                            
                            statusText.text = "Done."
                            btnSend.isEnabled = true
                            isQueryProcessed = true
                            
                            if (finalMsg.isNotBlank()) {
                                val utteranceId = if (finalMsg.trim().endsWith("?")) "QUESTION" else "STATEMENT"
                                tts?.speak(finalMsg, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
                            android.util.Log.e("AssistantSession", "LLM Error", throwable)
                            statusText.text = "Error"
                            responseText.text = throwable.message ?: "An unexpected error occurred."
                            btnSend.isEnabled = true
                            LLMManager.isFirstMessage = true
                            
                            kotlinx.coroutines.delay(2000)
                            finish()
                        }
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
            statusText.text = "Error"
            responseText.text = "An unexpected error occurred."
            btnSend.isEnabled = true
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

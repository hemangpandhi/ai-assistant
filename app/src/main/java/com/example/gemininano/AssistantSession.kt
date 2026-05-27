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
        
        if (LLMManager.llmInference == null) {
            statusText.text = "Initializing Model..."
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.GONE
            
            CoroutineScope(Dispatchers.Main).launch {
                LLMManager.autoInitialize(context, object : LLMManager.InitCallback {
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
        if (LLMManager.llmInference == null) return
        
        statusText.text = "Thinking..."
        responseText.text = ""
        lastResponseBuilder.clear()
        btnSend.isEnabled = false
        isQueryProcessed = false
        
        // Timeout watchdog
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.delay(30000) // 30 sec timeout
            if (!isQueryProcessed) {
                statusText.text = "Timeout."
                responseText.text = "Model took too long."
                btnSend.isEnabled = true
                try {
                    val inference = LLMManager.llmInference!!
                    val implicitSessionField = inference.javaClass.getDeclaredField("implicitSession")
                    implicitSessionField.isAccessible = true
                    val sessionRef = implicitSessionField.get(inference) as java.util.concurrent.atomic.AtomicReference<*>
                    val session = sessionRef.get()
                    if (session != null) {
                        val cancelMethod = session.javaClass.getDeclaredMethod("cancelGenerateResponseAsync")
                        cancelMethod.isAccessible = true
                        cancelMethod.invoke(session)
                    }
                } catch (e: Exception) {}
                kotlinx.coroutines.delay(2000)
                finish() // Auto-dismiss
            }
        }
        val modelName = LLMManager.currentModelPath.lowercase()
        val isGemma = modelName.contains("gemma")
        
        val systemPrompt = if (isGemma) {
            "You are an in-car AI. Current Temp: ${VehicleManager.getRealTemperature()}F, Speed: ${VehicleManager.getRealSpeed()}mph, Fuel: ${VehicleManager.getFuelLevel()}%.\n" +
            "You must ONLY output valid JSON.\n" +
            "Example 1:\nUser: 'increase temp by 5 degrees'\n{\"action\": \"increase_temperature\", \"value\": 5, \"message\": \"Increasing temperature by 5 degrees.\"}\n" +
            "Example 2:\nUser: 'set temperature to 74F'\n{\"action\": \"set_temperature\", \"value\": 74, \"message\": \"Setting temperature to 74 degrees.\"}\n" +
            "Example 3:\nUser: 'decrease temp by 10%'\n{\"action\": \"decrease_temperature\", \"value\": ${(VehicleManager.getRealTemperature() * 0.1).toInt()}, \"message\": \"Decreasing temperature by 10%.\"}\n" +
            "Example 4:\nUser: 'defrost windshield'\n{\"action\": \"defrost\", \"status\": true, \"message\": \"Defroster on.\"}\n" +
            "Example 5:\nUser: 'hello'\n{\"action\": \"chat\", \"message\": \"Hi there!\"}\n\nUser: '$query'\n"
        } else {
            // Simplified prompt for SmolLM-135M to prevent context overflow and hallucinations
            "You are an in-car AI. Output ONLY JSON.\n" +
            "User: 'increase temp'\n{\"action\": \"increase_temperature\", \"value\": 2, \"message\": \"Done\"}\n" +
            "User: 'defrost'\n{\"action\": \"defrost\", \"status\": true, \"message\": \"Done\"}\n" +
            "User: '$query'\n"
        }
               
        try {
            LLMManager.llmInference?.generateResponseAsync(systemPrompt) { partialResult, done ->
            CoroutineScope(Dispatchers.Main).launch {
                if (isQueryProcessed) return@launch // Stop processing if we already found the JSON
                
                var cleanResult = partialResult
                if (cleanResult.contains("<end_of_turn>")) cleanResult = cleanResult.replace("<end_of_turn>", "")
                if (cleanResult.contains("<eos>")) cleanResult = cleanResult.replace("<eos>", "")
                if (cleanResult.contains("<turn|>")) cleanResult = cleanResult.replace("<turn|>", "")
                if (cleanResult.contains("<|im_end|>")) cleanResult = cleanResult.replace("<|im_end|>", "")
                if (cleanResult.contains("im_end")) cleanResult = cleanResult.replace("im_end", "")
                if (cleanResult.contains("<|im_start|>")) cleanResult = cleanResult.replace("<|im_start|>", "")
                
                lastResponseBuilder.append(cleanResult)
                val currentText = lastResponseBuilder.toString()
                
                // Early JSON detection to bypass infinite generation bugs on mismatched models
                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(currentText)
                if (jsonMatch != null) {
                    try {
                        val jsonString = jsonMatch.value
                        val json = org.json.JSONObject(jsonString)
                        val action = json.optString("action")
                        
                        if (action == "set_temperature") {
                            val temp = json.optDouble("value", VehicleManager.getRealTemperature().toDouble())
                            VehicleManager.writeTemperatureToVhal(temp.toFloat())
                        } else if (action == "increase_temperature") {
                            val amount = json.optDouble("value", 2.0)
                            val currentTemp = VehicleManager.getRealTemperature().toDouble()
                            VehicleManager.writeTemperatureToVhal((currentTemp + amount).toFloat())
                        } else if (action == "decrease_temperature") {
                            val amount = json.optDouble("value", 2.0)
                            val currentTemp = VehicleManager.getRealTemperature().toDouble()
                            VehicleManager.writeTemperatureToVhal((currentTemp - amount).toFloat())
                        } else if (action == "defrost") {
                            VehicleManager.writeDefrosterToVhal(json.optBoolean("status", true))
                        }
                        
                        val displayMsg = json.optString("message", "Done.")
                        responseText.text = displayMsg
                        
                        statusText.text = "Done."
                        btnSend.isEnabled = true
                        isQueryProcessed = true
                        
                        if (displayMsg.isNotBlank()) {
                            tts?.speak(displayMsg, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                        
                        // Forcefully terminate the background generation via Reflection
                        // This prevents the model from continuing to generate tokens and causing IllegalStateExceptions
                        try {
                            val inference = LLMManager.llmInference!!
                            val implicitSessionField = inference.javaClass.getDeclaredField("implicitSession")
                            implicitSessionField.isAccessible = true
                            val sessionRef = implicitSessionField.get(inference) as java.util.concurrent.atomic.AtomicReference<*>
                            val session = sessionRef.get()
                            if (session != null) {
                                val cancelMethod = session.javaClass.getDeclaredMethod("cancelGenerateResponseAsync")
                                cancelMethod.isAccessible = true
                                cancelMethod.invoke(session)
                                android.util.Log.i("AssistantSession", "Forcefully terminated LLM generation early.")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "Failed to forcefully terminate LLM", e)
                        }
                        
                        // Auto-dismiss after 2 seconds
                        kotlinx.coroutines.delay(2000)
                        finish()
                        
                        return@launch
                    } catch (e: Exception) {
                        // JSON not yet fully formed, continue accumulating
                    }
                }
                
                if (done && !isQueryProcessed) {
                    android.util.Log.e("AssistantSession", "Failed to parse JSON. Full response: $currentText")
                    responseText.text = "Error processing request."
                    statusText.text = "Error."
                    btnSend.isEnabled = true
                    
                    kotlinx.coroutines.delay(2000)
                    finish()
                } else if (!isQueryProcessed) {
                    val msgIndex = currentText.indexOf("\"message\": \"")
                    if (msgIndex != -1) {
                        var extracted = currentText.substring(msgIndex + 12)
                        val endQuoteIndex = extracted.indexOf("\"")
                        if (endQuoteIndex != -1) {
                            extracted = extracted.substring(0, endQuoteIndex)
                        }
                        responseText.text = extracted
                    }
                }
            }
            }
        } catch (e: IllegalStateException) {
            statusText.text = "Model Busy"
            responseText.text = "Please wait for the previous query to finish processing."
            btnSend.isEnabled = true
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

package com.example.gemininano

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    private lateinit var overlayView: View
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnOpenApp: Button
    private lateinit var inputControls: View
    
    private var lastResponseBuilder = java.lang.StringBuilder()

    override fun onCreateContentView(): View {
        VehicleManager.initialize(context)
        overlayView = layoutInflater.inflate(R.layout.assistant_overlay, null)
        
        statusText = overlayView.findViewById(R.id.assistantStatusText)
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)
        btnOpenApp = overlayView.findViewById(R.id.btnOpenApp)
        inputControls = etInput.parent as View

        btnOpenApp.setOnClickListener {
            val intent = android.content.Intent(context, LocalLLMActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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
        
        return overlayView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        if (LLMManager.llmInference == null) {
            statusText.text = "Model not loaded. Please open the main app first."
            btnOpenApp.visibility = View.VISIBLE
            inputControls.visibility = View.GONE
        } else {
            statusText.text = "Hi, how can I help you?"
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.VISIBLE
            btnSend.isEnabled = true
        }
    }

    private fun handleQuery(query: String) {
        if (LLMManager.llmInference == null) return
        
        statusText.text = "Thinking..."
        responseText.text = ""
        lastResponseBuilder.clear()
        btnSend.isEnabled = false
        
        val systemPrompt = "You are a concise in-car AI assistant.\n" +
               "Current state: Speed ${VehicleManager.getRealSpeed()}mph, Cabin Temp ${VehicleManager.getRealTemperature()}F, Heater ${VehicleManager.getRealSeatHeaterLevel()}.\n" +
               "RULES:\n" +
               "1. Keep answers under 15 words.\n" +
               "2. If user asks to increase temp, output EXACTLY <TEMP_UP> and a short confirmation like 'Temperature increased by 4 degrees.'\n" +
               "3. If user asks to decrease temp, output EXACTLY <TEMP_DOWN> and a short confirmation.\n" +
               "4. Be direct, no fluff.\n" +
               "User: '$query'\nAssistant:"
               
        LLMManager.llmInference?.generateResponseAsync(systemPrompt) { partialResult, done ->
            CoroutineScope(Dispatchers.Main).launch {
                var cleanResult = partialResult
                if (cleanResult.contains("<end_of_turn>")) cleanResult = cleanResult.replace("<end_of_turn>", "")
                if (cleanResult.contains("<eos>")) cleanResult = cleanResult.replace("<eos>", "")
                if (cleanResult.contains("<turn|>")) cleanResult = cleanResult.replace("<turn|>", "")
                if (cleanResult.contains("<|im_end|>")) cleanResult = cleanResult.replace("<|im_end|>", "")
                if (cleanResult.contains("im_end")) cleanResult = cleanResult.replace("im_end", "")
                if (cleanResult.contains("<|im_start|>")) cleanResult = cleanResult.replace("<|im_start|>", "")
                
                lastResponseBuilder.append(cleanResult)
                
                var displayString = lastResponseBuilder.toString()
                
                if (displayString.contains("<TEMP_UP>")) {
                    val newTemp = VehicleManager.getRealTemperature() + 4
                    VehicleManager.writeTemperatureToVhal(newTemp.toFloat())
                    displayString = displayString.replace("<TEMP_UP>", "")
                }
                if (displayString.contains("<TEMP_DOWN>")) {
                    val newTemp = VehicleManager.getRealTemperature() - 4
                    VehicleManager.writeTemperatureToVhal(newTemp.toFloat())
                    displayString = displayString.replace("<TEMP_DOWN>", "")
                }
                
                responseText.text = displayString
                
                if (done) {
                    statusText.text = "Done."
                    btnSend.isEnabled = true
                }
            }
        }
    }
}

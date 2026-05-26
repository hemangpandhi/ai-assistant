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
    
    private var lastResponseBuilder = java.lang.StringBuilder()

    override fun onCreateContentView(): View {
        overlayView = layoutInflater.inflate(R.layout.assistant_overlay, null)
        
        statusText = overlayView.findViewById(R.id.assistantStatusText)
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)

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
            btnSend.isEnabled = false
        } else {
            statusText.text = "Hi, how can I help you?"
            btnSend.isEnabled = true
        }
    }

    private fun handleQuery(query: String) {
        if (LLMManager.llmInference == null) return
        
        statusText.text = "Thinking..."
        responseText.text = ""
        lastResponseBuilder.clear()
        btnSend.isEnabled = false
        
        val systemPrompt = "You are an in-car Android Automotive assistant. " +
               "The user says: '$query'."
               
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
                responseText.text = lastResponseBuilder.toString()
                
                if (done) {
                    statusText.text = "Done."
                    btnSend.isEnabled = true
                }
            }
        }
    }
}

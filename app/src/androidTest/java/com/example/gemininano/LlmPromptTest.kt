package com.example.gemininano

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlmPromptTest {
    @Test
    fun testAllScenarios() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalFilesDir = context.getExternalFilesDir(null)
        var modelFile = externalFilesDir?.listFiles { file ->
            file.extension == "litertlm"
        }?.firstOrNull()
        
        if (modelFile == null || !modelFile.exists()) {
            modelFile = File("/data/local/tmp/SmolLM-135M-Instruct.task")
            if (!modelFile.exists()) {
                throw Exception("Model not found in external files OR /data/local/tmp!")
            }
        }
        
        val modelPath = modelFile.absolutePath
        
        Log.d("TEST_REPORT", "Loading model...")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(64)
            .build()
            
        val llmInference = LlmInference.createFromOptions(context, options)
        
        val prompts = listOf(
            "increase temperature"
        )
        
        for ((index, prompt) in prompts.withIndex()) {
            Log.d("TEST_REPORT", "Running Scenario ${index + 1}...")
            val systemPrompt = "You are a concise in-car AI assistant.\n" +
                "Current state: Speed 0mph, Cabin Temp 70F, Heater Off, Fuel Level 100%, Gear Park.\n" +
                "RULES:\n" +
                "1. You must respond in valid JSON format ONLY. Do not include extra text.\n" +
                "2. If user asks to change temp, output: {\"action\": \"set_temperature\", \"value\": 75, \"message\": \"[confirmation]\"} (replace 75 with requested temp)\n" +
                "3. If user asks to defrost, output: {\"action\": \"defrost\", \"status\": true, \"message\": \"[confirmation]\"}\n" +
                "4. For all other queries, output: {\"action\": \"chat\", \"message\": \"[your answer]\"}\n" +
                "5. If Gear is Drive, refuse any distracting requests for safety.\n" +
                "User: '$prompt'\n" +
                "Assistant:"
            val response = try {
                llmInference.generateResponse(systemPrompt).replace("\n", " ").trim()
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            Log.d("TEST_REPORT", "SCENARIO_${index + 1}_RESULT| $response")
        }
    }
}

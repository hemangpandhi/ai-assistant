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
            modelFile = File("/data/local/tmp/gemma-4-E2B-it.litertlm")
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
            "I'm running low on gas and my kids are hungry. Find the nearest rest stop or family restaurant along my current route on I-5.",
            "The check engine light just came on, and the dashboard says OBD Code P0420. Is it safe to drive 50 more miles?",
            "I'm freezing and the sun is glaring right into my eyes. Provide the exact JSON commands to adjust the HVAC and sunshades.",
            "Sensors indicate the driver is falling asleep! Output EXACTLY this JSON: {\"action\": \"sound_alarm\"} and provide a short, urgent voice message to wake them up.",
            "Play some relaxing jazz music and turn the volume down a bit.",
            "Read my last text message and reply that I am driving and will be there in 15 minutes.",
            "Roll down all the windows and set the ambient lighting to a calming blue.",
            "How do I enable adaptive cruise control on the highway?"
        )
        
        for ((index, prompt) in prompts.withIndex()) {
            Log.d("TEST_REPORT", "Running Scenario ${index + 1}...")
            val systemPrompt = "You are an in-car Android Automotive assistant. The user says: '$prompt'."
            val response = try {
                llmInference.generateResponse(systemPrompt).replace("\n", " ").trim()
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            Log.d("TEST_REPORT", "SCENARIO_${index + 1}_RESULT| $response")
        }
    }
}

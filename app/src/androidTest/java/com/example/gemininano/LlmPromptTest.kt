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
        val tmpDir = File("/data/local/tmp/")
        val internalDir = context.filesDir
        
        val allFiles = listOfNotNull(internalDir?.listFiles(), externalFilesDir?.listFiles(), tmpDir.listFiles())
            .flatMap { it.toList() }

        var modelFile = allFiles.find { it.name.contains("SmolLM", ignoreCase = true) }
        
        if (modelFile == null || !modelFile.exists()) {
            throw Exception("SmolLM Model not found on device for testing!")
        }
        
        val modelPath = modelFile.absolutePath
        
        Log.d("TEST_REPORT", "Loading model from $modelPath")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build() 
            
        val llmInference = LlmInference.createFromOptions(context, options)
        
        val promptsPool = listOf(
            "increase temperature",
            "decrease temperature",
            "set temperature to 72",
            "turn on defrost",
            "turn off defrost",
            "how are you?",
            "make it hotter",
            "make it colder",
            "defrost the windshield",
            "set temp to 68"
        )
        
        Log.d("TEST_REPORT", "Starting 100 iterations test...")
        var successCount = 0
        var failCount = 0
        var totalLatency = 0L
        
        for (index in 0 until 100) {
            val prompt = promptsPool[index % promptsPool.size]
            val systemPrompt = "You are a concise in-car AI assistant.\n" +
                "Current state: Speed 0mph, Cabin Temp 70F, Heater Off, Fuel Level 100%, Gear Park.\n" +
                "RULES:\n" +
                "1. You must respond in valid JSON format ONLY. Do not include extra text.\n" +
                "2. To set exact temp: {\"action\": \"set_temperature\", \"value\": 72, \"message\": \"Setting temperature to 72 degrees.\"}\n" +
                "3. To increase temp: {\"action\": \"increase_temperature\", \"value\": 2, \"message\": \"Increasing temperature.\"}\n" +
                "4. To decrease temp: {\"action\": \"decrease_temperature\", \"value\": 2, \"message\": \"Decreasing temperature.\"}\n" +
                "5. To defrost: {\"action\": \"defrost\", \"status\": true, \"message\": \"Defrosting the windshield.\"}\n" +
                "6. For chat: {\"action\": \"chat\", \"message\": \"[your answer]\"}\n" +
                "User: '$prompt'\n" +
                "Assistant: {"

            val startTime = System.currentTimeMillis()
            var response = ""
            var isValidJson = false
            try {
                // Generate and manually prepend the forced brace
                val rawResponse = llmInference.generateResponse(systemPrompt)
                response = "{" + rawResponse.replace("\n", " ").trim()
                
                // Extremely simple JSON check
                if (response.contains("\"action\"") && response.endsWith("}")) {
                    isValidJson = true
                    successCount++
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                response = "ERROR: ${e.message}"
                failCount++
            }
            val latency = System.currentTimeMillis() - startTime
            totalLatency += latency
            
            Log.d("TEST_REPORT", "SCENARIO_${index + 1}_RESULT| JSON_VALID=$isValidJson | LATENCY=${latency}ms | PROMPT='$prompt' | RESPONSE='$response'")
        }
        
        val avgLatency = totalLatency / 100
        Log.d("TEST_REPORT", "=== TEST_SUMMARY ===")
        Log.d("TEST_REPORT", "Total Runs: 100")
        Log.d("TEST_REPORT", "Successful JSON: $successCount")
        Log.d("TEST_REPORT", "Failed/Corrupted JSON: $failCount")
        Log.d("TEST_REPORT", "Average Latency: ${avgLatency}ms")
        Log.d("TEST_REPORT", "====================")
    }
}

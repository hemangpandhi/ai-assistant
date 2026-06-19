package com.example.gemininano

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback

@RunWith(AndroidJUnit4::class)
class LLMValidationTestSuite {

    @get:Rule
    val grantPermissionRule: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO,
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CONTROL_CAR_SEATS",
        "android.car.permission.CONTROL_CAR_CLIMATE",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_INFO",
        "android.car.permission.READ_CAR_DISPLAY_UNITS",
        "android.car.permission.CAR_CAMERA"
    )

    private class TestCase(val prompt: String, val expectedToolTag: Regex)

    private val testCases = listOf(
        TestCase("I'm freezing", Regex("<TOOL>(increaseTemperature|handleFeelingCold)\\(.*\\)</TOOL>")),
        TestCase("Turn down the heat", Regex("<TOOL>decreaseTemperature\\(.*\\)</TOOL>")),
        TestCase("Navigate to San Francisco", Regex("<TOOL>navigate\\(.*\\)</TOOL>")),
        TestCase("Call my mechanic", Regex("<TOOL>(call|callContact)\\(.*\\)</TOOL>")),
        TestCase("Play some relaxing music", Regex("<TOOL>playMusic\\(.*\\)</TOOL>")),
        TestCase("Open the driver window", Regex("<TOOL>setWindowPosition\\(.*\\)</TOOL>")),
        TestCase("How much battery is left?", Regex("<TOOL>(getBatteryLevel|explainLowRange|optimizeEnergyForRange)\\(.*\\)</TOOL>")),
        TestCase("My windshield is fogged up", Regex("<TOOL>defogWindshield\\(.*\\)</TOOL>")),
        TestCase("The air inside smells bad", Regex("<TOOL>protectFromPollutedAir\\(.*\\)</TOOL>")),
        TestCase("I feel sleepy", Regex("<TOOL>(handleDrowsyDriving|handleDriverFatigue|adjustSeatPosition)\\(.*\\)</TOOL>")),
        TestCase("I need to go to the airport", Regex("<TOOL>prepareForAirportTrip\\(.*\\)</TOOL>")),
        TestCase("Turn off the AC", Regex("<TOOL>turnOffAC\\(.*\\)</TOOL>")),
        TestCase("Open all windows", Regex("<TOOL>(setAllWindowsPosition|setWindowPosition)\\(.*\\)</TOOL>")),
        TestCase("Open the trunk", Regex("<TOOL>openTrunk\\(.*\\)</TOOL>"))
    )

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ToolManager.initialize(context)
        VehicleManager.initialize(context)
    }

    @Test
    fun runLLMValidationSuite() {
        runBlocking {
        Log.d("LLMTest", "Starting LLM Validation Test Suite")
        
        val initSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                LLMManager.autoInitialize(context, callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {
                        Log.d("LLMTest", "LLM Initialized Headlessly")
                        continuation.resume(true)
                    }
                    override fun onError(e: Exception) {
                        Log.e("LLMTest", "LLM Init Failed", e)
                        continuation.resume(false)
                    }
                })
            }
        }
        
        assertTrue("LLM Initialization failed", initSuccess)
        assertNotNull("Engine should not be null", LLMManager.engine)
        assertNotNull("Conversation should not be null", LLMManager.conversation)

        var totalTTFT = 0L
        var maxTTFT = 0L
        
        for (case in testCases) {
            Log.d("LLMTest", "Testing Prompt: ${case.prompt}")
            LLMManager.resetConversation(context)
            
            val systemPrompt = LLMManager.getSystemPrompt(context, case.prompt)
            val finalPrompt = "$systemPrompt\n(Reminder: Use exact <TOOL> XML tags for car actions.)\nUser: ${case.prompt}"

            val startTime = System.currentTimeMillis()
            var firstTokenTime = 0L
            val responseBuilder = StringBuilder()

            try {
                val finalResponse = suspendCancellableCoroutine<String> { continuation ->
                    val callback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (firstTokenTime == 0L) {
                                firstTokenTime = System.currentTimeMillis()
                                val ttft = firstTokenTime - startTime
                                Log.d("LLMTest", "TTFT for '${case.prompt}': ${ttft}ms")
                                totalTTFT += ttft
                                if (ttft > maxTTFT) maxTTFT = ttft
                                assertTrue("TTFT exceeded threshold! ($ttft ms)", ttft < 15000)
                            }
                            val chunk = message.toString()
                            responseBuilder.append(chunk)
                            
                            val currentText = responseBuilder.toString()
                            if (currentText.contains("</TOOL>")) {
                                if (continuation.isActive) {
                                    continuation.resume(currentText)
                                }
                            }
                        }
                        
                        override fun onDone() {
                            if (continuation.isActive) {
                                continuation.resume(responseBuilder.toString())
                            }
                        }
                        
                        override fun onError(throwable: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(Exception(throwable))
                            }
                        }
                    }
                    
                    try {
                        LLMManager.conversation!!.sendMessageAsync(
                            Contents.of(Content.Text(finalPrompt)),
                            callback,
                            emptyMap()
                        )
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            delay(20000)
                            if (continuation.isActive) {
                                continuation.resume(responseBuilder.toString())
                            }
                        }
                    } catch(e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }

                Log.d("LLMTest", "Response: $finalResponse")

                val match = case.expectedToolTag.find(finalResponse)
                assertNotNull("Hallucination or missed tool! Expected ${case.expectedToolTag} but got: $finalResponse", match)
                
                val toolCall = match!!.value
                Log.d("LLMTest", "Extracted Tool Call: $toolCall")
                
                val resultString = ToolManager.executeToolCall(context, toolCall) { _ -> }
                assertNotNull("Tool execution returned null", resultString)
                Log.d("LLMTest", "Tool Execution Result: $resultString")
                
            } catch (e: Exception) {
                fail("Exception during generation for prompt '${case.prompt}': ${e.message}")
            }
        }
        
        Log.d("LLMTest", "Validation Suite Complete! Avg TTFT: ${totalTTFT / testCases.size}ms. Max TTFT: ${maxTTFT}ms.")
        }
    }
}

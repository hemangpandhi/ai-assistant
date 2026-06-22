package com.example.gemininano

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class MultiTurnTestSuite {

    private lateinit var context: Context

    private class Turn(val prompt: String, val expectedFinalTag: Regex?)

    private val scenarios = mapOf(
        "Personalized Dining (3 Turns)" to listOf(
            Turn("I am hungry.", null),
            Turn("Let's do Italian instead.", null),
            Turn("Mario's.", Regex("<TOOL>startNavigationTo\\(.*Mario's.*\\)</TOOL>"))
        )
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ToolManager.initialize(context)
        VehicleManager.initialize(context)
    }

    @Test
    fun runMultiTurnValidationSuite() {
        runBlocking {
            Log.d("MultiTurnTest", "Starting Multi-Turn Validation Test Suite")
            
            val initSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    LLMManager.autoInitialize(context, callback = object : LLMManager.InitCallback {
                        override fun onSuccess() {
                            Log.d("MultiTurnTest", "LLM Initialized Headlessly")
                            continuation.resume(true)
                        }
                        override fun onError(e: Exception) {
                            Log.e("MultiTurnTest", "LLM Init Failed", e)
                            continuation.resume(false)
                        }
                    })
                }
            }
            
            assertTrue("LLM Initialization failed", initSuccess)
            assertNotNull("Engine should not be null", LLMManager.engine)
            assertNotNull("Conversation should not be null", LLMManager.conversation)

            for ((scenarioName, turns) in scenarios) {
                Log.d("MultiTurnTest", "--- Starting Scenario: $scenarioName ---")
                LLMManager.resetConversation(context) // Reset once per scenario
                
                var finalResponse = ""
                val previousExecutedTools = mutableSetOf<String>()
                
                for ((index, turn) in turns.withIndex()) {
                    Log.d("MultiTurnTest", "Turn ${index + 1}: ${turn.prompt}")
                    val systemPrompt = LLMManager.getSystemPrompt(context, turn.prompt, previousExecutedTools)
                    val finalPrompt = "$systemPrompt\n(Reminder: Use exact <TOOL> XML tags for car actions.)\nUser: ${turn.prompt}"

                    val responseBuilder = StringBuilder()
                    
                    try {
                        val turnResponse = suspendCancellableCoroutine<String> { continuation ->
                            val callback = object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    responseBuilder.append(message.toString())
                                    val currentText = responseBuilder.toString()
                                    if (currentText.contains("</TOOL>") && turn.expectedFinalTag != null) {
                                        if (continuation.isActive) continuation.resume(currentText)
                                    }
                                }
                                override fun onDone() {
                                    if (continuation.isActive) continuation.resume(responseBuilder.toString())
                                }
                                override fun onError(throwable: Throwable) {
                                    if (continuation.isActive) continuation.resumeWithException(Exception(throwable))
                                }
                            }
                            
                            val conversation = LLMManager.conversation
                            if (conversation != null) {
                                try {
                                    conversation.sendMessageAsync(finalPrompt, callback)
                                } catch (e: Exception) {
                                    Log.e("MultiTurnTest", "LiteRT Error", e)
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                }
                            }
                        }
                        
                        Log.d("MultiTurnTest", "Response Turn ${index + 1}: $turnResponse")
                        finalResponse = turnResponse
                        
                        val toolMatch = Regex("<TOOL>([a-zA-Z0-9_]+)\\(.*\\)</TOOL>").find(finalResponse)
                        if (toolMatch != null) {
                            previousExecutedTools.add(toolMatch.groupValues[1])
                            Log.d("MultiTurnTest", "Extracted previous tool: ${toolMatch.groupValues[1]}")
                        }
                        
                        // Validate final expected tag if this is the last turn
                        if (turn.expectedFinalTag != null) {
                            val match = turn.expectedFinalTag.find(finalResponse)
                            assertTrue("Scenario $scenarioName failed! Expected ${turn.expectedFinalTag} but got: $finalResponse", match != null)
                            Log.d("MultiTurnTest", "SCENARIO SUCCESS: $scenarioName")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("MultiTurnTest", "Test crashed on turn ${index + 1}", e)
                        throw e
                    }
                }
            }
        }
    }
}

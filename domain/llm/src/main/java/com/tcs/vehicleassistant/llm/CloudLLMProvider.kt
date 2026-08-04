package com.tcs.vehicleassistant.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudLLMProvider(private val conversationMemory: com.tcs.vehicleassistant.ConversationMemory) : ILLMProvider {
    private var isInitialized = false
    
    override suspend fun initialize(context: Context, force: Boolean) {
        // Cloud providers don't need heavy loading, just flag as ready
        isInitialized = true
    }

    override suspend fun generateStream(
        context: Context,
        prompt: String,
        userQuery: String,
        onToken: (String) -> Unit,
        onDone: (String, List<com.tcs.vehicleassistant.utils.ParsedToolCall>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Use the orchestrator-assembled [prompt] as the API system instruction. Recomputing
        // LLMManager.getSystemPrompt here dropped telemetry injection, capability reminders, and
        // agentic observation framing that only AgentOrchestrator builds.
        val systemPrompt = prompt

        val responseBuilder = StringBuilder()
        val callback = object : CloudMessageCallback {
            override fun onMessage(chunkText: String) {
                responseBuilder.append(chunkText)
                onToken(chunkText)
            }
            override fun onDone() {
                onDone(responseBuilder.toString(), emptyList())
            }
            override fun onError(throwable: Throwable) {
                onError(Exception(throwable))
            }
        }

        try {
            if (EngineStatusStore.currentCloudModelName.contains("Gemini")) {
                GeminiManager.sendMessageAsync(systemPrompt, userQuery, conversationMemory.snapshot(), callback)
            } else {
                AnthropicManager.sendMessageAsync(systemPrompt, userQuery, conversationMemory.snapshot(), callback)
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    override fun unload() {
        isInitialized = false
    }

    override fun resetConversation() {
        // Cloud conversational state is usually handled per-request via history array
    }

    override fun isReady(): Boolean = isInitialized
}

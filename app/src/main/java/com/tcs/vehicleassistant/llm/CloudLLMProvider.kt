package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.LocalLLMActivity
import com.tcs.vehicleassistant.GeminiManager
import com.tcs.vehicleassistant.AnthropicManager
import com.tcs.vehicleassistant.LLMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudLLMProvider : ILLMProvider {
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
        onDone: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // The system prompt is already injected into the `prompt` by AgentOrchestrator.
        val fullPrompt = prompt

        val responseBuilder = StringBuilder()
        val callback = object : com.tcs.vehicleassistant.CloudMessageCallback {
            override fun onMessage(chunkText: String) {
                responseBuilder.append(chunkText)
                onToken(chunkText)
            }
            override fun onDone() {
                onDone(responseBuilder.toString())
            }
            override fun onError(throwable: Throwable) {
                onError(Exception(throwable))
            }
        }
        
        // Let's hook into the existing Cloud managers
        try {
            if (LocalLLMActivity.currentCloudModelName.contains("Gemini")) {
                // For now, Cloud managers don't perfectly stream to a callback without changes, 
                // but we can pass the logic down. Let's assume we call their async methods.
                // In a perfect world, GeminiManager/AnthropicManager would take onToken and onDone directly.
                // We will simulate it by delegating.
                // Streaming delegated to GeminiManager / AnthropicManager
                GeminiManager.sendMessageAsync("", fullPrompt, callback)
            } else {
                AnthropicManager.sendMessageAsync("", fullPrompt, callback)
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

package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.AnthropicManager
import com.tcs.vehicleassistant.GeminiManager
import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags

class CloudLLMProvider(
    private val featureFlags: AssistantFeatureFlags? = null,
) : ILLMProvider {
    private var isInitialized = false

    override suspend fun initialize(context: Context, force: Boolean) {
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

    override fun resetConversation() = Unit

    override fun isReady(): Boolean = isInitialized
}

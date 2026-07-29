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
        // The orchestrator assembles the operating rules, tool catalogue, and telemetry into
        // [prompt]. That whole block has to arrive as the API's system instruction: passing it as
        // the user message meant the cloud managers dropped it entirely (they build the request
        // body from MemoryManager history plus the systemPrompt argument), so cloud mode ran with
        // no tools and no safety rules.
        val systemPrompt = LLMManager.getSystemPrompt(context, userQuery)

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
                GeminiManager.sendMessageAsync(systemPrompt, userQuery, callback)
            } else {
                AnthropicManager.sendMessageAsync(systemPrompt, userQuery, callback)
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

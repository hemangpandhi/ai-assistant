package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.AnthropicManager
import com.tcs.vehicleassistant.GeminiManager
import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags

class CloudLLMProvider(
    private val featureFlags: AssistantFeatureFlags,
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
        val sysPrompt = LLMManager.getSystemPrompt(context, userQuery)
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
            val modelName = featureFlags.cloudModelName.ifBlank {
                com.tcs.vehicleassistant.LocalLLMActivity.currentCloudModelName
            }
            if (modelName.contains("Gemini")) {
                GeminiManager.sendMessageAsync(sysPrompt, prompt, callback)
            } else {
                AnthropicManager.sendMessageAsync(sysPrompt, prompt, callback)
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

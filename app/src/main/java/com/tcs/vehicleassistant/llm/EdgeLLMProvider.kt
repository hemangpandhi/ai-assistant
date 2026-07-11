package com.tcs.vehicleassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.LatencyLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Thin facade over [LLMManager] — ensures a single on-device engine instance
 * is shared across the voice overlay, config UI, and wake-word prewarm path.
 */
class EdgeLLMProvider : ILLMProvider {

    override suspend fun initialize(context: Context, force: Boolean) {
        if (!force && LLMManager.isReady()) return

        var initError: Exception? = null
        withContext(Dispatchers.IO) {
            LLMManager.autoInitialize(
                context,
                force = force,
                callback = object : LLMManager.InitCallback {
                    override fun onSuccess() {}
                    override fun onError(e: Exception) {
                        initError = e
                    }
                }
            )
        }
        initError?.let { throw it }
        if (!LLMManager.isReady()) {
            throw Exception("Edge LLM failed to initialize — no model found on device.")
        }
    }

    override suspend fun generateStream(
        context: Context,
        prompt: String,
        userQuery: String,
        onToken: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        while (LLMManager.isPrewarming) {
            delay(50)
        }

        if (!LLMManager.isReady()) {
            onError(Exception("Edge LLM not initialized"))
            return
        }

        val promptToUse = if (LLMManager.isFirstMessage) {
            LLMManager.isFirstMessage = false
            val sysPrompt = LLMManager.getSystemPrompt(context, userQuery)
            "$sysPrompt\n\n$prompt"
        } else {
            prompt
        }

        val responseBuilder = StringBuilder()
        val streamStart = System.currentTimeMillis()
        var firstTokenAt = -1L
        var tokenChunks = 0

        try {
            LLMManager.conversation!!.sendMessageAsync(
                Contents.of(Content.Text(promptToUse)),
                object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val textContent = message.contents?.contents?.firstOrNull() as? Content.Text
                        val text = textContent?.text ?: ""
                        if (text.isEmpty()) return

                        tokenChunks++
                        if (firstTokenAt < 0) {
                            firstTokenAt = System.currentTimeMillis()
                            LatencyLogger.log("EdgeLLM", "TTFT: ${firstTokenAt - streamStart}ms")
                        }

                        responseBuilder.append(text)
                        onToken(responseBuilder.toString())
                    }

                    override fun onDone() {
                        val elapsed = System.currentTimeMillis() - streamStart
                        if (tokenChunks > 0 && elapsed > 0) {
                            val tps = tokenChunks * 1000.0 / elapsed
                            LatencyLogger.log("EdgeLLM", "Chunks: $tokenChunks, throughput: ${"%.1f".format(tps)}/s")
                        }
                        onDone(responseBuilder.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        onError(Exception(throwable))
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
            onError(e)
        }
    }

    override fun unload() {
        LLMManager.unload()
        Log.i("EdgeLLMProvider", "Delegated unload to LLMManager")
    }

    override fun resetConversation() {
        LLMManager.resetConversation()
    }

    override fun isReady(): Boolean = LLMManager.isReady()
}

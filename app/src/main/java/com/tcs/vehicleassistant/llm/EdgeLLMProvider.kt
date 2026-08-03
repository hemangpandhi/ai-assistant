package com.tcs.vehicleassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.LatencyLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
        onDone: (String, List<com.tcs.vehicleassistant.utils.ParsedToolCall>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!LLMManager.isReady()) {
            onError(Exception("Edge LLM not initialized"))
            return
        }

        // The system prompt and model-specific wrappers (<start_of_turn>) are already injected 
        // by AgentOrchestrator for Gemma. But for Llama, it only outputs JSON, so we must wrap it here.
        val isLlama = LLMManager.currentModelPath?.contains("llama", ignoreCase = true) == true || LLMManager.currentModelPath?.contains("handoff", ignoreCase = true) == true
        val promptToUse = if (isLlama) {
            "<|start_header_id|>user<|end_header_id|>\n\n$prompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        } else {
            prompt
        }

        // Claim the engine for the duration of this inference so a concurrent unload() or
        // re-initialization cannot close the native conversation under the streaming callbacks.
        val activeConversation = LLMManager.beginInference()
        if (activeConversation == null) {
            onError(Exception("Edge LLM engine was released before inference could start"))
            return
        }

        val responseBuilder = StringBuilder()
        val streamStart = System.currentTimeMillis()
        var firstTokenAt = -1L
        var tokenChunks = 0
        val nativeToolCalls = mutableListOf<com.tcs.vehicleassistant.utils.ParsedToolCall>()

        // LiteRT's sendMessageAsync returns immediately and streams on its own thread. Awaiting
        // completion here keeps the inference inside the caller's coroutine, so cancellation and
        // the engine claim above both have a well-defined scope.
        val completion = CompletableDeferred<Unit>()

        try {
            activeConversation.sendMessageAsync(
                Contents.of(Content.Text(promptToUse)),
                object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val textContent = message.contents?.contents?.firstOrNull() as? Content.Text
                        val text = textContent?.text ?: ""
                        
                        if (message.toolCalls.isNotEmpty()) {
                            message.toolCalls.forEach { call ->
                                val argsList = call.arguments.values.map { it.toString() }
                                val argsStr = argsList.joinToString(",")
                                val toolTag = "<TOOL>${call.name}($argsStr)</TOOL>"
                                nativeToolCalls.add(com.tcs.vehicleassistant.utils.ParsedToolCall(toolTag, call.name, argsStr))
                                try {
                                    onToken("\n$toolTag")
                                } catch (e: Exception) {}
                            }
                        }

                        if (text.isEmpty()) return

                        tokenChunks++
                        if (firstTokenAt < 0) {
                            firstTokenAt = System.currentTimeMillis()
                            LatencyLogger.log("EdgeLLM", "TTFT: ${firstTokenAt - streamStart}ms")
                        }

                        responseBuilder.append(text)
                        try {
                            onToken(text)
                        } catch (e: Exception) {
                            Log.e("EdgeLLMProvider", "onToken consumer threw; continuing stream", e)
                        }
                    }

                    override fun onDone() {
                        val elapsed = System.currentTimeMillis() - streamStart
                        if (tokenChunks > 0 && elapsed > 0) {
                            val tps = tokenChunks * 1000.0 / elapsed
                            LatencyLogger.log("EdgeLLM", "Chunks: $tokenChunks, throughput: ${"%.1f".format(tps)}/s")
                        }
                        try {
                            onDone(responseBuilder.toString(), nativeToolCalls)
                        } finally {
                            completion.complete(Unit)
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        try {
                            onError(Exception(throwable))
                        } finally {
                            completion.complete(Unit)
                        }
                    }
                },
                emptyMap()
            )
            kotlinx.coroutines.withTimeout(60_000L) {
                completion.await()
            }
        } catch (e: Exception) {
            onError(e)
        } finally {
            LLMManager.endInference()
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

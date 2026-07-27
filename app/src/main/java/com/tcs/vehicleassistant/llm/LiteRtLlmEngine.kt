package com.tcs.vehicleassistant.llm

import android.content.Context
import android.util.Log
import com.tcs.vehicleassistant.LLMManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Injectable LiteRT engine that owns readiness via [LLMManager] while
 * exposing the [LlmEngine] port (replaces direct object access over time).
 */
class LiteRtLlmEngine : LlmEngine {

    override val status: StateFlow<EngineStatus>
        get() = LLMManager.engineStatus

    override suspend fun ensureReady(context: Context, force: Boolean) {
        if (!force && LLMManager.isInferenceReady()) return
        LLMManager.autoInitialize(context.applicationContext, force = force)
        // Wait briefly for prewarm if still running
        var spins = 0
        while (LLMManager.isPrewarming && spins < 600) {
            kotlinx.coroutines.delay(50)
            spins++
        }
    }

    override fun generateStream(request: LlmRequest): Flow<TokenChunk> = callbackFlow {
        while (LLMManager.isPrewarming && isActive) {
            kotlinx.coroutines.delay(50)
        }
        val conversation = LLMManager.conversation
        if (conversation == null) {
            close(IllegalStateException("LiteRT conversation not ready"))
            return@callbackFlow
        }

        val startTime = System.currentTimeMillis()
        var firstToken = true
        val fullPrompt = "<start_of_turn>user\n${request.prompt}<end_of_turn>\n<start_of_turn>model\n"

        conversation.sendMessageAsync(
            com.google.ai.edge.litertlm.Contents.of(
                com.google.ai.edge.litertlm.Content.Text(fullPrompt)
            ),
            object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val textContent = message.contents?.contents?.firstOrNull() as? com.google.ai.edge.litertlm.Content.Text
                        val text = textContent?.text ?: ""
                        if (text.isEmpty()) return
                        if (firstToken) {
                            firstToken = false
                            com.tcs.vehicleassistant.LatencyLogger.log(
                                "LiteRtLlmEngine",
                                "TTFT: ${System.currentTimeMillis() - startTime}ms",
                            )
                        }
                        trySend(TokenChunk(text))
                    }

                override fun onDone() {
                    close()
                }

                override fun onError(throwable: Throwable) {
                    close(throwable)
                }
            },
            emptyMap(),
        )

        awaitClose {
            Log.d(TAG, "generateStream closed")
        }
    }

    override suspend fun unload() {
        LLMManager.unload()
    }

    override fun resetConversation() {
        LLMManager.resetConversation()
    }

    override fun isReady(): Boolean = LLMManager.isInferenceReady()

    companion object {
        private const val TAG = "LiteRtLlmEngine"
    }
}

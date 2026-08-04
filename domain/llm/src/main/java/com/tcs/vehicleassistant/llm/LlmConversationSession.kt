package com.tcs.vehicleassistant.llm

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Single responsibility: LiteRT [Conversation] create / reset and OpenAPI tool binding.
 */
object LlmConversationSession {
    private const val TAG = "LlmConversationSession"

    @Volatile
    var conversation: Conversation? = null
        private set

    /**
     * Gallery-aligned conversation options: sampler on GPU/CPU, stable system instruction.
     * Query-specific tools still ride on each user turn via the orchestrator.
     */
    @OptIn(ExperimentalApi::class)
    fun buildConfig(userQuery: String = ""): ConversationConfig {
        val sampler = if (EngineStatusStore.speculativeDecodingActive) {
            null
        } else if (EngineStatusStore.activeBackendString == AssistantConfig.Backend.NPU) {
            null
        } else {
            SamplerConfig(
                topK = AssistantConfig.Llm.SAMPLER_TOP_K,
                topP = AssistantConfig.Llm.SAMPLER_TOP_P,
                temperature = AssistantConfig.Llm.SAMPLER_TEMPERATURE,
            )
        }
        val systemInstruction = Contents.of(
            Content.Text(
                "You are the in-vehicle AI co-pilot with live cabin, media, and navigation tools. Keep answers concise."
            )
        )

        val toolSchemaGenerator = getKoin().get<ToolSchemaGenerator>()
        val schemas = toolSchemaGenerator.getOpenApiSchemas(userQuery)
        val providers = schemas.map { schema ->
            tool(DynamicOpenApiTool(schema.second))
        }

        return ConversationConfig(
            systemInstruction = systemInstruction,
            samplerConfig = sampler,
            tools = providers
        )
    }

    /**
     * Recycles the LiteRT conversation to bound KV-cache growth.
     *
     * @return false when an inference is still in flight — the caller should wait and retry rather
     * than force-closing the conversation under a live stream.
     */
    fun reset(userQuery: String = ""): Boolean {
        return LlmInferenceGate.withLock {
            if (LiteRtEngineHost.engine == null) {
                EngineStatusStore.markIdleConversationCounters()
                return@withLock true
            }
            val active = LlmInferenceGate.activeCount()
            if (active > 0) {
                Log.w(TAG, "Skipping conversation reset: $active inference(s) still in flight")
                return@withLock false
            }

            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous conversation", e)
            }
            conversation = null
            EngineStatusStore.lastAiResponse = ""

            try {
                conversation = LiteRtEngineHost.engine?.createConversation(buildConfig(userQuery))
                EngineStatusStore.isFirstMessage = true
                EngineStatusStore.nativeTurnsSinceReset = 0
                Log.d(
                    TAG,
                    "Conversation reset. isFirstMessage=true backend=${EngineStatusStore.activeBackendString} " +
                        "speculativeWas=${EngineStatusStore.speculativeDecodingActive}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset conversation", e)
                return@withLock false
            }
            true
        }
    }

    /**
     * Closes the conversation. Caller must already hold [LlmInferenceGate] and ensure
     * no inference is active (used by [LiteRtEngineHost.closeAllLocked] / unload).
     */
    fun detachUnderLock() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close conversation", e)
        }
        conversation = null
    }

    /**
     * Schema-only OpenAPI tool: LiteRT needs the description JSON on the conversation; execution
     * still goes through the app tool executor / orchestrator path.
     */
    private class DynamicOpenApiTool(private val jsonString: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = jsonString
        override fun execute(args: String): String = "{}"
    }
}

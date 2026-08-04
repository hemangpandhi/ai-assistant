package com.tcs.vehicleassistant.assistant.agent

import android.content.Context
import com.tcs.vehicleassistant.llm.ILLMProvider
import com.tcs.vehicleassistant.utils.ParsedToolCall

/**
 * In-memory [ILLMProvider] for code-level tests — no network, no LiteRT.
 *
 * Orchestrator integration can inject this via Koin in Robolectric tests later; goldens today
 * use [TurnPipelineSimulator] which does not need a Context.
 */
class FakeLlmProvider(
    private val reply: String = "Okay.",
    private val tools: List<ParsedToolCall> = emptyList(),
) : ILLMProvider {

    var initializeCalls: Int = 0
        private set
    var generateCalls: Int = 0
        private set
    var lastPrompt: String? = null
        private set
    var lastUserQuery: String? = null
        private set
    var ready: Boolean = true

    override suspend fun initialize(context: Context, force: Boolean) {
        initializeCalls++
        ready = true
    }

    override suspend fun generateStream(
        context: Context,
        prompt: String,
        userQuery: String,
        onToken: (String) -> Unit,
        onDone: (String, List<ParsedToolCall>) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        generateCalls++
        lastPrompt = prompt
        lastUserQuery = userQuery
        if (!ready) {
            onError(Exception("FakeLlmProvider not ready"))
            return
        }
        // Emit in small chunks to exercise stream scrubbing if wired later.
        var i = 0
        while (i < reply.length) {
            val end = minOf(i + 12, reply.length)
            onToken(reply.substring(i, end))
            i = end
        }
        onDone(reply, tools)
    }

    override fun unload() {
        ready = false
    }

    override fun resetConversation() = Unit

    override fun isReady(): Boolean = ready
}

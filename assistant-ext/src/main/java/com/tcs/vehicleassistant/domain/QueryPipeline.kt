package com.tcs.vehicleassistant.domain

import android.content.Context
import com.assistant.api.llm.LlmSessionPort
import com.assistant.api.tools.ToolCatalog
import com.tcs.vehicleassistant.SmartContextInjector
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags
import com.tcs.vehicleassistant.data.memory.ConversationMemory

/**
 * Builds the edge/cloud prompt for one turn — keeps prompt policy out of the orchestrator loop.
 */
class QueryPipeline(
    private val toolCatalog: ToolCatalog,
    private val memory: ConversationMemory,
    private val featureFlags: AssistantFeatureFlags,
    private val llmSession: LlmSessionPort,
) {
    data class BuiltPrompt(
        val prompt: String,
        val userOrSystemText: String,
    )

    suspend fun build(
        context: Context,
        interceptedQuery: String,
        isAgenticObservation: Boolean,
    ): BuiltPrompt {
        val maxHistoryChars = toolCatalog.slidingWindowMaxChars
        val isFollowUp = memory.isFollowUpQuery(interceptedQuery)
        val historyCap =
            if (isFollowUp || interceptedQuery.length < 30) {
                maxHistoryChars
            } else {
                minOf(1000, maxHistoryChars)
            }
        val priorHistory = memory.getSlidingWindowContext(historyCap)

        if (isAgenticObservation) {
            memory.addTurn("System", interceptedQuery)
        } else {
            memory.captureLongTermFacts(context, interceptedQuery)
            memory.addTurn("User", interceptedQuery)
        }

        val sysPrompt = llmSession.getSystemPrompt(context, interceptedQuery)
        val looksCommand = SpeculativeToolPrep.looksLikeCommand(interceptedQuery)
        val needsTelemetry = !isAgenticObservation &&
            !looksCommand &&
            (interceptedQuery.length >= 50 || isFollowUp)
        val dynamicState = if (needsTelemetry) {
            SmartContextInjector.getInjectedContext(interceptedQuery, context)
        } else {
            ""
        }
        val vehicleState = if (dynamicState.isNotEmpty()) "[Current State: $dynamicState]" else ""

        val stateInject = if (vehicleState.isNotEmpty() && vehicleState != llmSession.lastVehicleState) {
            llmSession.lastVehicleState = vehicleState
            "$vehicleState\n"
        } else {
            ""
        }

        val currentToolsString =
            toolCatalog.llmToolsPrompt(interceptedQuery, llmSession.lastAiResponse)
        val toolsInject =
            if (currentToolsString.isNotBlank() && currentToolsString != llmSession.lastInjectedTools) {
                llmSession.lastInjectedTools = currentToolsString
                "\n[Available Tools]\n$currentToolsString\n"
            } else {
                ""
            }

        val prompt = if (featureFlags.isCloudActive) {
            val history = if (priorHistory.isNotEmpty()) priorHistory else "(start)"
            val toolsBlock =
                if (currentToolsString.isNotBlank()) "\n[Available Tools]\n$currentToolsString\n" else ""
            "$sysPrompt\n\n[Conversation History]\n$history\n\n$vehicleState$toolsBlock" +
                "User: $interceptedQuery\nAssistant:"
        } else {
            "$stateInject$toolsInject$interceptedQuery"
        }
        return BuiltPrompt(prompt = prompt, userOrSystemText = interceptedQuery)
    }
}

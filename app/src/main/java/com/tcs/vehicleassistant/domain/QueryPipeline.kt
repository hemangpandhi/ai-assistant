package com.tcs.vehicleassistant.domain

import android.content.Context
import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.SmartContextInjector
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags
import com.tcs.vehicleassistant.data.memory.ConversationMemory

/**
 * Builds the edge/cloud prompt for one turn — keeps prompt policy out of the orchestrator loop.
 */
class QueryPipeline(
    private val toolManager: ToolManager,
    private val memory: ConversationMemory,
    private val featureFlags: AssistantFeatureFlags,
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
        val maxHistoryChars = toolManager.slidingWindowMaxChars
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

        val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
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

        val stateInject = if (vehicleState.isNotEmpty() && vehicleState != LLMManager.lastVehicleState) {
            LLMManager.lastVehicleState = vehicleState
            "$vehicleState\n"
        } else {
            ""
        }

        val currentToolsString =
            toolManager.getLlmToolsPrompt(interceptedQuery, LLMManager.lastAiResponse)
        val toolsInject =
            if (currentToolsString.isNotBlank() && currentToolsString != LLMManager.lastInjectedTools) {
                LLMManager.lastInjectedTools = currentToolsString
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

package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConversationSafetyPolicy
import com.tcs.vehicleassistant.core.LlmToolAllowList
import com.tcs.vehicleassistant.core.LlmToolTurnPolicy

/**
 * Plans LLM tool actions without executing VHAL / handlers / ContextGuard I/O.
 *
 * Orchestrator applies [PlannedToolAction] results; this type stays free of Android + LiteRT.
 */
object ToolLoopPlanner {

    data class ParsedTool(
        val toolName: String,
        val args: String,
    ) {
        val toolCall: String get() = "$toolName($args)"
    }

    sealed class PlannedToolAction {
        abstract val toolCall: String

        data class RejectCrisisEntertainment(
            override val toolCall: String,
            val spokenFeedback: String,
        ) : PlannedToolAction()

        data class RejectAllowList(
            override val toolCall: String,
            val message: String,
        ) : PlannedToolAction()

        data class RequireConfirmation(
            override val toolCall: String,
            val askMessage: String,
        ) : PlannedToolAction()

        /** Ready for ContextGuard + execute on a worker — planner does not run them. */
        data class ScheduleExecute(
            override val toolCall: String,
        ) : PlannedToolAction()
    }

    /**
     * @param isAllowed prompt allow-list check for [ParsedTool.toolName]
     * @param confirmationAsk null when the tool does not require a soft confirm stash
     */
    fun plan(
        tools: List<ParsedTool>,
        userQuery: String,
        alreadyExecuted: Set<String>,
        isAllowed: (toolName: String) -> Boolean,
        confirmationAsk: (toolCall: String, toolName: String) -> String?,
    ): List<PlannedToolAction> {
        val planned = mutableListOf<PlannedToolAction>()
        val seen = alreadyExecuted.toMutableSet()
        for (parsed in tools) {
            val toolCall = parsed.toolCall
            if (!seen.add(toolCall)) continue

            if (ConversationSafetyPolicy.forbidsEntertainmentOffer(userQuery) &&
                ConversationSafetyPolicy.isEntertainmentTool(toolCall)
            ) {
                planned += PlannedToolAction.RejectCrisisEntertainment(
                    toolCall = toolCall,
                    spokenFeedback = ConversationSafetyPolicy.evaluate(userQuery).spokenResponse,
                )
                continue
            }

            if (!isAllowed(parsed.toolName)) {
                planned += PlannedToolAction.RejectAllowList(
                    toolCall = toolCall,
                    message = LlmToolAllowList.rejectionMessage(parsed.toolName),
                )
                continue
            }

            val ask = confirmationAsk(toolCall, parsed.toolName)
            if (ask != null) {
                planned += PlannedToolAction.RequireConfirmation(toolCall, ask)
                break
            } else {
                planned += PlannedToolAction.ScheduleExecute(toolCall)
            }
        }
        return planned
    }

    /** Helper used by orchestrator when registry marks requiresConfirmation. */
    fun confirmationAskMessage(toolName: String, registryMessage: String?): String =
        LlmToolTurnPolicy.confirmationAskMessage(toolName, registryMessage)
}

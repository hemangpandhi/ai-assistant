package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConversationSafetyPolicy

/**
 * Pure, side-effect-free composition of the agent turn pieces for **code-level** verification.
 *
 * No Android Context, LiteRT, audio, VHAL, or Koin. Production [AgentOrchestrator] remains the
 * runtime wiring; this type lets CI prove multi-turn policy without a car or model file.
 */
class TurnPipelineSimulator(
    val confirms: ConfirmationCoordinator = ConfirmationCoordinator(),
    val turns: TurnStateMachine = TurnStateMachine(),
) {

    data class RoutedTurn(
        val query: String,
        val decision: TurnRouter.Decision,
        val turnId: Long,
    )

    data class ModelTurnResult(
        val displayReply: String,
        val plannedTools: List<ToolLoopPlanner.PlannedToolAction>,
        val chatHint: String,
        val stashedSoftMusicOffer: Boolean,
    )

    /**
     * Simulate a user utterance through normalize → supersede → route (same order as orchestrator).
     */
    fun userSays(
        raw: String,
        directHit: TurnRouter.DirectHit? = null,
        followUpToolCall: String? = null,
        modelReady: Boolean = true,
        cloudModelActive: Boolean = false,
        isAffirmativeKeepAlive: Boolean? = null,
    ): RoutedTurn {
        val normalized = TurnRouter.normalize(raw)
        confirms.applySupersedeIfNeeded(normalized.query)
        val snap = confirms.snapshot()
        val affirmative = isAffirmativeKeepAlive
            ?: AFFIRMATIVES.contains(normalized.lowerLettersOnly)
        val decision = TurnRouter.resolve(
            TurnRouter.Input(
                query = normalized.query,
                pendingConfirmationTool = snap.pendingConfirmationTool,
                pendingOfferedTool = snap.pendingOfferedTool,
                isAffirmativeKeepAlive = affirmative,
                directHit = directHit,
                followUpToolCall = followUpToolCall,
                modelReady = modelReady,
                cloudModelActive = cloudModelActive,
            ),
        )
        // Mirror orchestrator clears for affirm/decline / crisis / wellness side effects that
        // affect the *next* turn's pending state.
        when (decision) {
            is TurnRouter.Decision.ContextGuardDecline,
            is TurnRouter.Decision.ContextGuardAffirm,
            -> confirms.clearAll()
            is TurnRouter.Decision.OfferDecline,
            is TurnRouter.Decision.OfferAffirm,
            -> confirms.clearOffer()
            is TurnRouter.Decision.CrisisSupport -> confirms.clearOffer()
            is TurnRouter.Decision.WellnessOffer -> confirms.setSoftOffer("playMusic(relaxing)")
            is TurnRouter.Decision.DirectTool,
            is TurnRouter.Decision.FollowUp,
            -> confirms.clearOffer()
            else -> Unit
        }
        val turnId = turns.beginTurn()
        turns.markProcessed()
        return RoutedTurn(normalized.query, decision, turnId)
    }

    /**
     * Simulate model completion: sanitize reply, plan tools, optionally stash soft music offer.
     */
    fun modelReplies(
        userQuery: String,
        modelRawReply: String,
        tools: List<ToolLoopPlanner.ParsedTool> = emptyList(),
        isAllowed: (String) -> Boolean = { true },
        confirmationAsk: (toolCall: String, toolName: String) -> String? = { _, _ -> null },
        offeredMusic: Boolean = false,
    ): ModelTurnResult {
        val display = StreamTextPolicy.sanitizeFinalReply(
            userQuery,
            StreamTextPolicy.normalizeForDisplay(modelRawReply),
        )
        val planned = ToolLoopPlanner.plan(
            tools = tools,
            userQuery = userQuery,
            alreadyExecuted = emptySet(),
            isAllowed = isAllowed,
            confirmationAsk = confirmationAsk,
        )
        for (action in planned) {
            when (action) {
                is ToolLoopPlanner.PlannedToolAction.RequireConfirmation ->
                    confirms.setConfirmation(action.toolCall)
                is ToolLoopPlanner.PlannedToolAction.RejectCrisisEntertainment,
                is ToolLoopPlanner.PlannedToolAction.RejectAllowList,
                is ToolLoopPlanner.PlannedToolAction.ScheduleExecute,
                -> Unit
            }
        }
        val isQuestion = display.trim().endsWith("?") ||
            display.contains("would you like", ignoreCase = true)
        val stashMusic = isQuestion &&
            offeredMusic &&
            !ConversationSafetyPolicy.forbidsEntertainmentOffer(userQuery)
        if (stashMusic) {
            confirms.setSoftOffer("playMusic(relaxing)")
        } else if (ConversationSafetyPolicy.forbidsEntertainmentOffer(userQuery)) {
            confirms.clearOffer()
        }
        return ModelTurnResult(
            displayReply = display,
            plannedTools = planned,
            chatHint = PromptAssembler.chatHint(userQuery),
            stashedSoftMusicOffer = stashMusic,
        )
    }

    companion object {
        private val AFFIRMATIVES = setOf("yes", "ok", "okay", "sure", "yep", "yeah", "yup")
    }
}

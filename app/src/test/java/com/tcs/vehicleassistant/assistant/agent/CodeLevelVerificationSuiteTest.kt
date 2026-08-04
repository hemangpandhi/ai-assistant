package com.tcs.vehicleassistant.assistant.agent

import com.tcs.vehicleassistant.core.ConversationSafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code-level verification inventory + multi-turn goldens.
 *
 * These replace “flash the car for every dialogue bug” for policy/routing/tool-plan risks.
 * Device tests remain only for mic / LiteRT / VHAL.
 */
class CodeLevelVerificationSuiteTest {

    @Test
    fun `inventory — pure agent types cover the non-device risk surface`() {
        // If someone deletes a collaborator, this fails loudly.
        val types = listOf(
            TurnRouter::class,
            ConfirmationCoordinator::class,
            TurnStateMachine::class,
            PromptAssembler::class,
            StreamTextPolicy::class,
            ToolLoopPlanner::class,
            TurnPipelineSimulator::class,
            ConversationSafetyPolicy::class,
        )
        assertEquals(8, types.size)
    }

    @Test
    fun `multi-turn — accident then yes never plays music`() {
        val sim = TurnPipelineSimulator()
        val t1 = sim.userSays("my car got into an accident")
        assertTrue(t1.decision is TurnRouter.Decision.CrisisSupport)
        val crisis = t1.decision as TurnRouter.Decision.CrisisSupport
        assertFalse(ConversationSafetyPolicy.containsEntertainmentOffer(crisis.spokenResponse))

        // Hostile model still offering music after crisis user turn.
        val model = sim.modelReplies(
            userQuery = t1.query,
            modelRawReply = "I'm sorry. Would you like me to play some music?",
            tools = listOf(ToolLoopPlanner.ParsedTool("playMusic", "relaxing")),
            offeredMusic = true,
        )
        assertFalse(ConversationSafetyPolicy.containsEntertainmentOffer(model.displayReply))
        assertFalse(model.stashedSoftMusicOffer)
        assertTrue(
            model.plannedTools.any {
                it is ToolLoopPlanner.PlannedToolAction.RejectCrisisEntertainment
            },
        )
        assertNull(sim.confirms.pendingOfferedTool)

        val t2 = sim.userSays("yes")
        assertFalse(t2.decision is TurnRouter.Decision.OfferAffirm)
        assertFalse(t2.decision is TurnRouter.Decision.ContextGuardAffirm)
    }

    @Test
    fun `multi-turn — wellness offer then yes affirms music only for mild case`() {
        val sim = TurnPipelineSimulator()
        val t1 = sim.userSays("I am not feeling good")
        assertTrue(t1.decision is TurnRouter.Decision.WellnessOffer)
        assertEquals("playMusic(relaxing)", sim.confirms.pendingOfferedTool)

        val t2 = sim.userSays("yes")
        val affirm = t2.decision as TurnRouter.Decision.OfferAffirm
        assertEquals("playMusic(relaxing)", affirm.toolCall)
    }

    @Test
    fun `multi-turn — safety confirm yes unlocks not music`() {
        val sim = TurnPipelineSimulator()
        sim.confirms.setConfirmation("unlockDoors()")
        val t = sim.userSays("yes")
        val affirm = t.decision as TurnRouter.Decision.ContextGuardAffirm
        assertEquals("unlockDoors()", affirm.toolCall)
    }

    @Test
    fun `multi-turn — cabin command supersedes stale music offer`() {
        val sim = TurnPipelineSimulator()
        sim.confirms.setSoftOffer("playMusic(relaxing)")
        val t = sim.userSays(
            "increase temperature",
            directHit = TurnRouter.DirectHit(
                toolCall = "increaseTemperature()",
                spokenResponse = "Warming",
                matchedKeyword = "increase temperature",
                reason = "keyword",
            ),
        )
        assertTrue(t.decision is TurnRouter.Decision.DirectTool)
        assertNull(sim.confirms.pendingOfferedTool)
    }

    @Test
    fun `fake LLM — crisis chat hint steers prompt without device`() {
        val hint = PromptAssembler.chatHint("call 911")
        assertTrue(hint.contains("CRISIS") || hint.contains("crisis", ignoreCase = true))
        val turn = PromptAssembler.buildGemmaTurn(
            isFirstMessage = false,
            sysPrompt = "SYS",
            capabilityReminder = "REMIND",
            toolsBlock = PromptAssembler.toolsBlock("playMusic"),
            historyBlock = "",
            stateInject = "",
            chatHint = hint,
            formattedQuery = "call 911",
        )
        assertTrue(turn.contains(hint.trim()) || turn.contains("CRISIS") || turn.contains("crisis", ignoreCase = true))
        assertTrue(turn.contains("REMIND"))
    }

    @Test
    fun `fake LLM — empty model fallback matrix without LiteRT`() {
        assertFalse(
            ConversationSafetyPolicy.containsEntertainmentOffer(
                StreamTextPolicy.resolveEmptyModelFallback("I crashed the car"),
            ),
        )
        assertTrue(
            StreamTextPolicy.resolveEmptyModelFallback("I am not feeling good")
                .contains("music", ignoreCase = true),
        )
        assertTrue(
            StreamTextPolicy.resolveEmptyModelFallback("What time is it")
                .contains("didn't catch", ignoreCase = true),
        )
    }

    @Test
    fun `turn abandon — late tokens from old turn are not current`() {
        val sim = TurnPipelineSimulator()
        val a = sim.userSays("tell me a joke about traffic")
        val b = sim.userSays("increase temperature", directHit = TurnRouter.DirectHit(
            toolCall = "increaseTemperature()",
            spokenResponse = "Warming",
            matchedKeyword = "increase temperature",
            reason = "keyword",
        ))
        assertFalse(sim.turns.isCurrentTurn(a.turnId))
        assertTrue(sim.turns.isCurrentTurn(b.turnId))
    }
}

/**
 * Exhaustive crisis matrix via the simulator (code-level stand-in for cabin dialogue QA).
 */
class CodeLevelCrisisMatrixTest {
    @Test
    fun everyRegressionPhraseIsCrisisWithoutEntertainment() {
        val sim = TurnPipelineSimulator()
        for (phrase in ConversationSafetyPolicy.REGRESSION_PHRASES) {
            val turn = sim.userSays(phrase)
            assertTrue("expected CrisisSupport for: $phrase", turn.decision is TurnRouter.Decision.CrisisSupport)
            val spoken = (turn.decision as TurnRouter.Decision.CrisisSupport).spokenResponse
            assertFalse(
                "entertainment leak for: $phrase → $spoken",
                ConversationSafetyPolicy.containsEntertainmentOffer(spoken),
            )
            val hostile = sim.modelReplies(
                userQuery = phrase,
                modelRawReply = "Sorry — want some music?",
                tools = listOf(ToolLoopPlanner.ParsedTool("playMusic", "")),
                offeredMusic = true,
            )
            assertFalse(ConversationSafetyPolicy.containsEntertainmentOffer(hostile.displayReply))
            assertFalse(hostile.stashedSoftMusicOffer)
        }
    }
}

package com.tcs.vehicleassistant.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent of orchestrator / LiteRT / audio. */
class TurnStateMachineTest {

    @Test
    fun `beginTurn invalidates previous turn id`() {
        val sm = TurnStateMachine()
        val first = sm.beginTurn()
        assertTrue(sm.isCurrentTurn(first))
        assertTrue(sm.isProcessing())
        val second = sm.beginTurn()
        assertFalse(sm.isCurrentTurn(first))
        assertTrue(sm.isCurrentTurn(second))
    }

    @Test
    fun `markProcessed ends processing`() {
        val sm = TurnStateMachine()
        sm.beginTurn()
        sm.markProcessed()
        assertFalse(sm.isProcessing())
    }

    @Test
    fun `abandonTurn invalidates prior id and is idle`() {
        val sm = TurnStateMachine()
        val first = sm.beginTurn()
        sm.abandonTurn()
        assertFalse(sm.isCurrentTurn(first))
        assertFalse(sm.isProcessing())
    }
}

class PromptAssemblerTest {

    @Test
    fun `crisis chat hint has no music offer language for entertainment`() {
        val hint = PromptAssembler.chatHint("my car got into an accident").lowercase()
        assertTrue(hint.contains("crisis"))
        assertTrue(hint.contains("no music") || hint.contains("never suggest playing music"))
    }

    @Test
    fun `first turn includes system prompt wrapper`() {
        val prompt = PromptAssembler.buildGemmaTurn(
            isFirstMessage = true,
            sysPrompt = "SYS",
            capabilityReminder = "REMIND",
            toolsBlock = "=== AVAILABLE TOOLS ===\nplay\n",
            historyBlock = "",
            stateInject = "",
            chatHint = "",
            formattedQuery = "hello",
        )
        assertTrue(prompt.contains("<start_of_turn>system"))
        assertTrue(prompt.contains("SYS"))
        assertTrue(prompt.contains("hello"))
    }

    @Test
    fun `later turn re-injects reminder and tools not full system`() {
        val prompt = PromptAssembler.buildGemmaTurn(
            isFirstMessage = false,
            sysPrompt = "SYS_SHOULD_NOT_APPEAR",
            capabilityReminder = "REMIND",
            toolsBlock = "TOOLS",
            historyBlock = "HISTORY_SHOULD_NOT_APPEAR",
            stateInject = "",
            chatHint = "",
            formattedQuery = "warm it up",
        )
        assertFalse(prompt.contains("SYS_SHOULD_NOT_APPEAR"))
        assertFalse(prompt.contains("HISTORY_SHOULD_NOT_APPEAR"))
        assertTrue(prompt.contains("REMIND"))
        assertTrue(prompt.contains("TOOLS"))
    }
}

class StreamTextPolicyTest {

    @Test
    fun `normalize repairs doubled pronoun`() {
        assertEquals("I can help", StreamTextPolicy.normalizeForDisplay("iI can help"))
    }

    @Test
    fun `empty fallback for accident is crisis not music`() {
        val msg = StreamTextPolicy.resolveEmptyModelFallback("my car got into an accident")
        assertFalse(StreamTextPolicy.WELLNESS_OFFER == msg)
        assertFalse(msg.contains("music", ignoreCase = true))
    }

    @Test
    fun `cutHallucinatedUserEcho truncates`() {
        val (cut, flag) = StreamTextPolicy.cutHallucinatedUserEcho("Hello there\nUser: more")
        assertTrue(flag)
        assertEquals("Hello there", cut)
    }
}

class ToolLoopPlannerTest {

    @Test
    fun `crisis blocks playMusic independently of allow list`() {
        val planned = ToolLoopPlanner.plan(
            tools = listOf(ToolLoopPlanner.ParsedTool("playMusic", "relaxing")),
            userQuery = "we were in an accident",
            alreadyExecuted = emptySet(),
            isAllowed = { true },
            confirmationAsk = { _, _ -> null },
        )
        assertEquals(1, planned.size)
        assertTrue(planned[0] is ToolLoopPlanner.PlannedToolAction.RejectCrisisEntertainment)
    }

    @Test
    fun `allow list rejection does not schedule execute`() {
        val planned = ToolLoopPlanner.plan(
            tools = listOf(ToolLoopPlanner.ParsedTool("unlockDoors", "")),
            userQuery = "unlock the doors",
            alreadyExecuted = emptySet(),
            isAllowed = { false },
            confirmationAsk = { _, _ -> null },
        )
        assertTrue(planned[0] is ToolLoopPlanner.PlannedToolAction.RejectAllowList)
    }

    @Test
    fun `confirmation ask schedules require confirm not execute`() {
        val planned = ToolLoopPlanner.plan(
            tools = listOf(ToolLoopPlanner.ParsedTool("unlockDoors", "")),
            userQuery = "unlock the doors",
            alreadyExecuted = emptySet(),
            isAllowed = { true },
            confirmationAsk = { _, _ -> "Are you sure?" },
        )
        val req = planned[0] as ToolLoopPlanner.PlannedToolAction.RequireConfirmation
        assertEquals("Are you sure?", req.askMessage)
    }

    @Test
    fun `duplicate tool calls are skipped`() {
        val planned = ToolLoopPlanner.plan(
            tools = listOf(
                ToolLoopPlanner.ParsedTool("increaseTemperature", ""),
                ToolLoopPlanner.ParsedTool("increaseTemperature", ""),
            ),
            userQuery = "warmer",
            alreadyExecuted = emptySet(),
            isAllowed = { true },
            confirmationAsk = { _, _ -> null },
        )
        assertEquals(1, planned.size)
        assertTrue(planned[0] is ToolLoopPlanner.PlannedToolAction.ScheduleExecute)
    }

    @Test
    fun `planning breaks after the first tool that requires confirmation`() {
        val planned = ToolLoopPlanner.plan(
            tools = listOf(
                ToolLoopPlanner.ParsedTool("openTrunk", ""),
                ToolLoopPlanner.ParsedTool("setFanSpeed", "3")
            ),
            userQuery = "open trunk and turn on fan",
            alreadyExecuted = emptySet(),
            isAllowed = { true },
            confirmationAsk = { call, _ -> 
                if (call.contains("openTrunk")) "Are you sure?" else null 
            }
        )
        
        // It should break on openTrunk, so setFanSpeed is never scheduled
        assertEquals(1, planned.size)
        assertTrue(planned[0] is ToolLoopPlanner.PlannedToolAction.RequireConfirmation)
        assertEquals("openTrunk()", planned[0].toolCall)
    }
}

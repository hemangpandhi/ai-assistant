package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmStabilityHardeningTest {

    @Test
    fun conversationResetPolicy_triggersAtThreshold() {
        assertFalse(ConversationResetPolicy.shouldResetBeforePrompt(0, 8))
        assertFalse(ConversationResetPolicy.shouldResetBeforePrompt(7, 8))
        assertTrue(ConversationResetPolicy.shouldResetBeforePrompt(8, 8))
        assertTrue(ConversationResetPolicy.shouldResetBeforePrompt(9, 8))
        assertFalse(ConversationResetPolicy.shouldResetBeforePrompt(8, 0))
    }

    @Test
    fun allowList_parsesAllowedToolsLineAndTags() {
        val prompt = """
            - <TOOL>playMusic(SONG)</TOOL>: Play media
            - <TOOL>pauseMusic()</TOOL>
            Allowed tools: playMusic, pauseMusic
        """.trimIndent()
        val keys = LlmToolAllowList.extractAllowedToolNames(prompt)
        assertTrue(keys.contains("playMusic"))
        assertTrue(keys.contains("pauseMusic"))
        assertEquals(2, keys.size)
    }

    @Test
    fun allowList_rejectsUnknownTool() {
        val allowed = setOf("playMusic", "pauseMusic")
        assertTrue(LlmToolAllowList.isAllowed("playMusic", allowed))
        assertTrue(LlmToolAllowList.isAllowed("pause", allowed, canonicalKey = "pauseMusic"))
        assertFalse(LlmToolAllowList.isAllowed("openTrunk", allowed))
        assertFalse(LlmToolAllowList.isAllowed("openTrunk", emptySet()))
    }

    @Test
    fun gemmaScorer_passesCorrectToolAndCatchesRefusal() {
        val play = GemmaRegressionScorer.Case("play", "play music", expectedToolName = "playMusic")
        val ok = GemmaRegressionScorer.score(
            play,
            "<TOOL>playMusic()</TOOL> Playing music for you right now.",
        )
        assertTrue(ok.passed)

        val refuse = GemmaRegressionScorer.score(
            play,
            "I am a large language model and I cannot control the vehicle.",
        )
        assertFalse(refuse.passed)
        assertTrue(refuse.reasons.any { it.startsWith("refusal_phrase") })
        assertTrue(refuse.reasons.contains("missing_tool"))
    }

    @Test
    fun gemmaScorer_wrongToolFails() {
        val play = GemmaRegressionScorer.Case("play", "play music", expectedToolName = "playMusic")
        val wrong = GemmaRegressionScorer.score(play, "<TOOL>stopMusic()</TOOL> Stopping.")
        assertFalse(wrong.passed)
        assertTrue(wrong.reasons.any { it.startsWith("wrong_tool") })
    }

    @Test
    fun gemmaScorer_chatOnlyForbidsTools() {
        val chat = GemmaRegressionScorer.Case("chat", "how are you?", requireTool = false)
        assertTrue(
            GemmaRegressionScorer.score(chat, "I'm doing well, thanks for asking.").passed,
        )
        assertFalse(
            GemmaRegressionScorer.score(chat, "<TOOL>playMusic()</TOOL> Playing.").passed,
        )
    }

    @Test
    fun gemmaScorer_suiteAggregates() {
        val cases = listOf(
            GemmaRegressionScorer.Case("a", "play music", "playMusic"),
            GemmaRegressionScorer.Case("b", "stop music", "stopMusic"),
        )
        val report = GemmaRegressionScorer.scoreSuite(
            cases,
            mapOf(
                "a" to "<TOOL>playMusic()</TOOL> Playing.",
                "b" to "I can't control playback.",
            ),
        )
        assertEquals(1, report.passed)
        assertEquals(1, report.failed)
        assertFalse(report.allPassed)
    }

    @Test
    fun defaultCabinCases_areNonEmpty() {
        assertTrue(GemmaRegressionScorer.defaultCabinCases().size >= 5)
    }
}

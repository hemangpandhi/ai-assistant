package com.tcs.vehicleassistant.repository

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two pure helpers the token stream runs through: display normalization and the runaway
 * cut-off. Both used to be inline inside `onToken`, where they could not be exercised without a
 * loaded model.
 */
class StreamTextHandlingTest {

    @Test
    fun `a doubled pronoun artifact collapses to one I`() {
        assertEquals("I can help", AgentOrchestrator.normalizeForDisplay("iI can help"))
    }

    @Test
    fun `the i-can-I artifact collapses to I can`() {
        assertEquals("I can do that", AgentOrchestrator.normalizeForDisplay("i can I do that"))
    }

    @Test
    fun `a bare leading i becomes I`() {
        assertEquals("I", AgentOrchestrator.normalizeForDisplay("i"))
    }

    @Test
    fun `a stray leading lowercase i token is dropped`() {
        // Carried over verbatim from the original inline normalization: this model emits a stray
        // leading "i " after the chat-template markers are stripped, and the pronoun is removed
        // rather than capitalized. Pinned here so any change to it is a deliberate one.
        assertEquals("can help you with that", AgentOrchestrator.normalizeForDisplay("i can help you with that"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("The cabin is warm.", AgentOrchestrator.normalizeForDisplay("  The cabin is warm.  \n"))
    }

    @Test
    fun `normal text passes through unchanged`() {
        val text = "Air conditioning is on and the cabin is cooling down."
        assertEquals(text, AgentOrchestrator.normalizeForDisplay(text))
    }

    @Test
    fun `an uppercase I mid-sentence is left alone`() {
        val text = "Sure, I will set it to 72 degrees."
        assertEquals(text, AgentOrchestrator.normalizeForDisplay(text))
    }

    @Test
    fun `empty input normalizes to empty output`() {
        assertEquals("", AgentOrchestrator.normalizeForDisplay(""))
        assertEquals("", AgentOrchestrator.normalizeForDisplay("   "))
    }

    @Test
    fun `text past the length ceiling is a runaway`() {
        val text = "a".repeat(AssistantConfig.Streaming.RUNAWAY_LENGTH + 1)
        assertTrue(AgentOrchestrator.isRunawayGeneration(text))
    }

    @Test
    fun `text at the length ceiling is not yet a runaway`() {
        val text = "a".repeat(AssistantConfig.Streaming.RUNAWAY_LENGTH)
        assertFalse(AgentOrchestrator.isRunawayGeneration(text))
    }

    @Test
    fun `a repeated tail word past the scan threshold is a runaway`() {
        val window = AssistantConfig.Streaming.REPETITION_WINDOW
        val filler = "padding ".repeat(AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH / 8 + 1)
        val text = filler + ("loop " .repeat(window)).trim()

        assertTrue("filler must exceed the scan threshold", text.length > AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH)
        assertTrue(AgentOrchestrator.isRunawayGeneration(text))
    }

    @Test
    fun `a short repetition below the scan threshold is not flagged`() {
        // Repetition scanning only starts past a minimum length, so a legitimately short answer
        // that happens to repeat a word is never truncated.
        val text = ("yes " .repeat(AssistantConfig.Streaming.REPETITION_WINDOW)).trim()
        assertTrue(text.length <= AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH)
        assertFalse(AgentOrchestrator.isRunawayGeneration(text))
    }

    @Test
    fun `varied long prose is not flagged as a runaway`() {
        val words = (1..60).joinToString(" ") { "word$it" }
        assertTrue(words.length > AssistantConfig.Streaming.REPETITION_SCAN_MIN_LENGTH)
        assertTrue(words.length < AssistantConfig.Streaming.RUNAWAY_LENGTH)
        assertFalse(AgentOrchestrator.isRunawayGeneration(words))
    }

    @Test
    fun `a repetition shorter than the window is not flagged`() {
        val filler = (1..60).joinToString(" ") { "word$it" }
        val text = "$filler " + ("loop " .repeat(AssistantConfig.Streaming.REPETITION_WINDOW - 1)).trim()
        assertFalse(AgentOrchestrator.isRunawayGeneration(text))
    }

    @Test
    fun `empty text is not a runaway`() {
        assertFalse(AgentOrchestrator.isRunawayGeneration(""))
    }

    @Test
    fun `empty model fallback never claims Done for questions or chat`() {
        val question = AgentOrchestrator.resolveEmptyModelFallback("What is the weather?")
        val chat = AgentOrchestrator.resolveEmptyModelFallback("I am feeling very sad and depressed")
        assertTrue(question.contains("didn't catch", ignoreCase = true))
        assertFalse(chat.contains("didn't catch", ignoreCase = true))
        assertTrue(chat.contains("music", ignoreCase = true) || chat.contains("here with you", ignoreCase = true))
        assertFalse(question.contains("Done", ignoreCase = true))
        assertFalse(chat.contains("taken care", ignoreCase = true))
    }

    @Test
    fun `empty model fallback for accident never offers music`() {
        val msg = AgentOrchestrator.resolveEmptyModelFallback("my car got into an accident")
        assertFalse(msg.contains("music", ignoreCase = true))
        assertFalse(msg.contains("Done", ignoreCase = true))
        assertTrue(
            msg.contains("emergency", ignoreCase = true) ||
                msg.contains("hurt", ignoreCase = true) ||
                msg.contains("okay", ignoreCase = true),
        )
    }

    @Test
    fun `empty model fallback for action-like phrases admits tool failure`() {
        val msg = AgentOrchestrator.resolveEmptyModelFallback("play some music")
        assertTrue(msg.contains("couldn't run a tool", ignoreCase = true))
        assertFalse(msg.contains("taken care", ignoreCase = true))
    }

    @Test
    fun `looksLikeUserQuestion detects trailing question mark and starters`() {
        assertTrue(AgentOrchestrator.looksLikeUserQuestion("How cold is it outside?"))
        assertTrue(AgentOrchestrator.looksLikeUserQuestion("what time is it"))
        assertFalse(AgentOrchestrator.looksLikeUserQuestion("I am feeling sad"))
    }

    @Test
    fun `empty catch is reserved for questions not emotional or echo noise`() {
        val question = AgentOrchestrator.resolveEmptyModelFallback("What time is it")
        val echoNoise = AgentOrchestrator.resolveEmptyModelFallback("and being said.")
        val emotional = AgentOrchestrator.resolveEmptyModelFallback("Feeling sad")
        assertTrue(question.contains("didn't catch", ignoreCase = true))
        assertTrue(echoNoise.isBlank())
        assertFalse(emotional.contains("didn't catch", ignoreCase = true))
        assertTrue(emotional.contains("music", ignoreCase = true) || emotional.contains("here with you", ignoreCase = true))
    }

    @Test
    fun `empty LLM no tools on not feeling good never yields Done ACK`() {
        val msg = AgentOrchestrator.resolveEmptyModelFallback("i am not feeling good")
        assertFalse(msg.contains("Done", ignoreCase = true))
        assertFalse(msg.contains("taken care", ignoreCase = true))
        assertFalse(msg.contains("didn't catch", ignoreCase = true))
        assertTrue(msg.contains("music", ignoreCase = true) || msg.contains("feeling", ignoreCase = true))
        assertEquals(AgentOrchestrator.WELLNESS_OFFER, msg)
    }

    @Test
    fun `bare yes empty fallback clarifies instead of didnt catch`() {
        val msg = AgentOrchestrator.resolveEmptyModelFallback("yes")
        assertFalse(msg.contains("didn't catch", ignoreCase = true))
        assertTrue(msg.isNotBlank())
        assertTrue(msg.contains("music", ignoreCase = true) || msg.contains("?"))
    }
}

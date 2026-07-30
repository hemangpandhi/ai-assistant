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
}

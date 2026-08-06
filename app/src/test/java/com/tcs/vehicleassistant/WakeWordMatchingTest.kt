package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the wake-word gate that decides whether a Vosk result opens the assistant.
 */
class WakeWordMatchingTest {

    private val wakeWord = AssistantConfig.WakeWord.DEFAULT_WAKE_WORD

    @Test
    fun `exact phrase matches`() {
        assertTrue(WakeWordService.matchesWakeWord("hey iris", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("hi car", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("hello sora", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("ok iris", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("okay car", wakeWord))
    }

    @Test
    fun `phrase embedded in a longer utterance matches`() {
        assertTrue(WakeWordService.matchesWakeWord("okay hey iris turn on the ac", wakeWord))
    }

    @Test
    fun `stale decoder leftovers with a repeated wake phrase do not rematch`() {
        assertFalse(WakeWordService.matchesWakeWord("hey [unk] hey iris", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hey iris hey iris", wakeWord))
    }

    @Test
    fun `a single wake phrase with unk noise around it still matches`() {
        assertTrue(WakeWordService.matchesWakeWord("hey [unk] iris", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("[unk] hey iris [unk]", wakeWord))
    }

    @Test
    fun `matching ignores case and surrounding whitespace`() {
        assertTrue(WakeWordService.matchesWakeWord("  HEY Iris  ", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("hey iris", "  HEY IRIS "))
    }

    @Test
    fun `bare greeting or name does not trigger`() {
        assertFalse(WakeWordService.matchesWakeWord("hi", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hello", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hey", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("iris", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("car", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("sora", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("assistant", wakeWord))
    }

    @Test
    fun `words in the wrong order do not trigger`() {
        assertFalse(WakeWordService.matchesWakeWord("iris hey", wakeWord))
    }

    @Test
    fun `the Vosk unknown token never triggers`() {
        assertFalse(WakeWordService.matchesWakeWord(AssistantConfig.WakeWord.UNKNOWN_TOKEN, wakeWord))
    }

    @Test
    fun `an empty transcript never triggers`() {
        assertFalse(WakeWordService.matchesWakeWord("", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("   ", wakeWord))
    }

    @Test
    fun `legacy assistant wake words do not trigger`() {
        assertFalse(WakeWordService.matchesWakeWord("hey assistant", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hello polestar", "hello polestar"))
    }

    @Test
    fun `extractTranscript reads a final result`() {
        assertEquals("hey iris", WakeWordService.extractTranscript("""{"text" : "hey iris"}"""))
    }

    @Test
    fun `extractTranscript reads a partial result`() {
        assertEquals("hey iri", WakeWordService.extractTranscript("""{"partial" : "hey iri"}"""))
    }

    @Test
    fun `extractTranscript tolerates spacing variants`() {
        assertEquals("turn on the ac", WakeWordService.extractTranscript("""{"text":"turn on the ac"}"""))
    }

    @Test
    fun `extractTranscript returns empty for an empty or unrecognised payload`() {
        assertEquals("", WakeWordService.extractTranscript("""{"text" : ""}"""))
        assertEquals("", WakeWordService.extractTranscript("""{"result" : []}"""))
        assertEquals("", WakeWordService.extractTranscript("not json at all"))
    }

    @Test
    fun `a transcript extracted from Vosk output feeds the matcher`() {
        val transcript = WakeWordService.extractTranscript("""{"text" : "Hey Iris, roll the window down"}""")
        assertTrue(WakeWordService.matchesWakeWord(transcript, wakeWord))
    }
}

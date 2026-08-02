package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the wake-word gate that decides whether a Vosk result opens the assistant. These were
 * previously "tested" by re-implementing `contains` inside the test body, which asserted nothing
 * about the service.
 */
class WakeWordMatchingTest {

    private val wakeWord = AssistantConfig.WakeWord.DEFAULT_WAKE_WORD

    @Test
    fun `exact phrase matches`() {
        assertTrue(WakeWordService.matchesWakeWord("hey assistant", wakeWord))
    }

    @Test
    fun `phrase embedded in a longer utterance matches`() {
        assertTrue(WakeWordService.matchesWakeWord("okay hey assistant turn on the ac", wakeWord))
    }

    @Test
    fun `stale decoder leftovers with a repeated wake phrase do not rematch`() {
        // After RESTART without reset, Vosk emitted e.g. "hey [unk] hey nissan" which still
        // contained the configured phrase and reopened the overlay without a new wake word.
        assertFalse(WakeWordService.matchesWakeWord("hey [unk] hey assistant", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hey [unk] hey [unk] hey assistant", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hey [unk] hey nissan", "hey nissan"))
        assertFalse(WakeWordService.matchesWakeWord("hey nissan hey nissan", "hey nissan"))
    }

    @Test
    fun `a single wake phrase with unk noise around it still matches`() {
        assertTrue(WakeWordService.matchesWakeWord("hey [unk] assistant", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("[unk] hey assistant [unk]", wakeWord))
    }

    @Test
    fun `matching ignores case and surrounding whitespace`() {
        assertTrue(WakeWordService.matchesWakeWord("  HEY Assistant  ", wakeWord))
        assertTrue(WakeWordService.matchesWakeWord("hey assistant", "  HEY ASSISTANT "))
    }

    @Test
    fun `a single word from the phrase does not trigger`() {
        // False triggers from bare "assistant" were the reason matching is a strict containment
        // check rather than a fuzzy or per-word one.
        assertFalse(WakeWordService.matchesWakeWord("assistant", wakeWord))
        assertFalse(WakeWordService.matchesWakeWord("hey", wakeWord))
    }

    @Test
    fun `words in the wrong order do not trigger`() {
        assertFalse(WakeWordService.matchesWakeWord("assistant hey", wakeWord))
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
    fun `an empty configured wake word never triggers`() {
        // Otherwise a blank preference would make every utterance a match.
        assertFalse(WakeWordService.matchesWakeWord("hey assistant", ""))
        assertFalse(WakeWordService.matchesWakeWord("anything at all", "   "))
    }

    @Test
    fun `a custom wake word is honoured`() {
        assertTrue(WakeWordService.matchesWakeWord("hello polestar, open the roof", "hello polestar"))
        assertFalse(WakeWordService.matchesWakeWord("hey assistant", "hello polestar"))
    }

    @Test
    fun `hey name also accepts bare name`() {
        assertTrue(WakeWordService.matchesWakeWord("hey iris", "hey iris"))
        assertTrue(WakeWordService.matchesWakeWord("iris", "hey iris"))
        assertTrue(WakeWordService.matchesWakeWord("okay iris open the map", "hey iris"))
        // Bare "assistant" stays rejected — too many cabin false wakes.
        assertFalse(WakeWordService.matchesWakeWord("assistant", "hey assistant"))
    }

    @Test
    fun `extractTranscript reads a final result`() {
        assertEquals("hey assistant", WakeWordService.extractTranscript("""{"text" : "hey assistant"}"""))
    }

    @Test
    fun `extractTranscript reads a partial result`() {
        assertEquals("hey assist", WakeWordService.extractTranscript("""{"partial" : "hey assist"}"""))
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
        val transcript = WakeWordService.extractTranscript("""{"text" : "Hey Assistant, roll the window down"}""")
        assertTrue(WakeWordService.matchesWakeWord(transcript, wakeWord))
    }
}

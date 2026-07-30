package com.assistant.ui.assistant.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveInputTextTest {

    @Test
    fun tokensKeepWhitespaceWithFollowingWord() {
        val tokens = liveInputTokens("Hey — find a coffee")
        assertEquals(listOf("Hey", " —", " find", " a", " coffee"), tokens)
        assertEquals("Hey — find a coffee", tokens.joinToString(""))
    }

    @Test
    fun emptyTextHasNoTokens() {
        assertTrue(liveInputTokens("").isEmpty())
    }

    @Test
    fun sharedPrefixCountsStableStem() {
        val previous = liveInputTokens("find a cafe")
        val next = liveInputTokens("find a coffee shop")
        assertEquals(2, liveInputSharedPrefixCount(previous, next))
    }

    @Test
    fun sharedPrefixIsZeroWhenDivergent() {
        val previous = liveInputTokens("Hello there")
        val next = liveInputTokens("On it — thinking")
        assertEquals(0, liveInputSharedPrefixCount(previous, next))
    }

    @Test
    fun tokenAlphaRisesSmoothlyAcrossFadeWindow() {
        assertEquals(0f, liveInputTokenAlpha(0, 0f), 0.001f)
        assertTrue(liveInputTokenAlpha(0, 0.8f) in 0.2f..0.8f)
        assertEquals(1f, liveInputTokenAlpha(0, 2f), 0.001f)
        // Overlapping edge: next token already easing in before previous settles.
        assertTrue(liveInputTokenAlpha(1, 1.2f) > 0f)
        assertTrue(liveInputTokenAlpha(1, 1.2f) < liveInputTokenAlpha(0, 1.2f))
    }

    @Test
    fun visibleTokensOmitUnrevealedWords() {
        val tokens = liveInputTokens("one two three four")
        assertTrue(liveInputVisibleTokens(tokens, 0f).isEmpty())

        val early = liveInputVisibleTokens(tokens, 0.8f)
        assertEquals(1, early.size)
        assertEquals("one", early[0].first.trim())

        val mid = liveInputVisibleTokens(tokens, 2.0f)
        assertEquals(2, mid.size)
        assertEquals("one two", mid.joinToString("") { it.first }.trim())

        val all = liveInputVisibleTokens(tokens, 6f)
        assertEquals(4, all.size)
        assertEquals("one two three four", all.joinToString("") { it.first }.trim())
    }

    @Test
    fun speakingRevealIsSlowerThanLiveWipe() {
        val live = liveInputRevealDurationMs(8, speaking = false)
        val speaking = liveInputRevealDurationMs(8, speaking = true)
        assertTrue(speaking > live)
        assertTrue(speaking >= 8 * 300)
    }
}

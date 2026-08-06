package com.tcs.vehicleassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordPhrasePolicyTest {

    @Test
    fun shortAliasDisabled() {
        assertNull(WakeWordPhrasePolicy.shortAlias("hey iris"))
        assertNull(WakeWordPhrasePolicy.shortAlias("OK Iris"))
        assertNull(WakeWordPhrasePolicy.shortAlias("iris"))
    }

    @Test
    fun grammarIsFixedAllowlist() {
        val phrases = WakeWordPhrasePolicy.grammarPhrases("hey assistant")
        assertTrue(phrases.contains("hey iris"))
        assertTrue(phrases.contains("hello sora"))
        assertTrue(phrases.contains("ok car"))
        assertTrue(phrases.contains("[unk]"))
        assertFalse(phrases.contains("iris"))
        assertFalse(phrases.contains("hey assistant"))
    }

    @Test
    fun matchesOnlyPrefixPlusName() {
        assertTrue(WakeWordPhrasePolicy.matches("hey iris", "ignored"))
        assertTrue(WakeWordPhrasePolicy.matches("hi car open the map", "ignored"))
        assertTrue(WakeWordPhrasePolicy.matches("hello sora", "ignored"))
        assertTrue(WakeWordPhrasePolicy.matches("okay iris", "ignored"))
        assertTrue(WakeWordPhrasePolicy.matches("ok car", "ignored"))

        assertFalse(WakeWordPhrasePolicy.matches("hi", "ignored"))
        assertFalse(WakeWordPhrasePolicy.matches("hello", "ignored"))
        assertFalse(WakeWordPhrasePolicy.matches("hey", "ignored"))
        assertFalse(WakeWordPhrasePolicy.matches("iris", "ignored"))
        assertFalse(WakeWordPhrasePolicy.matches("hey assistant", "ignored"))
        assertFalse(WakeWordPhrasePolicy.matches("hello polestar", "ignored"))
    }

    @Test
    fun normalizeKwsPhonemeSpacing() {
        assertEquals("hey iris", WakeWordPhrasePolicy.normalizeKwsKeyword("H EY I R I S"))
        assertEquals("hi car", WakeWordPhrasePolicy.normalizeKwsKeyword("h i c a r"))
        assertEquals("hello sora", WakeWordPhrasePolicy.normalizeKwsKeyword("hello sora"))
    }
}

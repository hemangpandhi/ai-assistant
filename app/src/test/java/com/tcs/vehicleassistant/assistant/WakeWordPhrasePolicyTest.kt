package com.tcs.vehicleassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordPhrasePolicyTest {

    @Test
    fun shortAliasForHeyName() {
        assertEquals("iris", WakeWordPhrasePolicy.shortAlias("hey iris"))
        assertEquals("iris", WakeWordPhrasePolicy.shortAlias("OK Iris"))
        assertNull(WakeWordPhrasePolicy.shortAlias("iris"))
        assertNull(WakeWordPhrasePolicy.shortAlias("hello polestar"))
        assertNull(WakeWordPhrasePolicy.shortAlias("hey assistant"))
    }

    @Test
    fun grammarIncludesBareName() {
        val phrases = WakeWordPhrasePolicy.grammarPhrases("hey iris")
        assertTrue(phrases.contains("hey iris"))
        assertTrue(phrases.contains("iris"))
        assertTrue(phrases.contains("[unk]"))
    }

    @Test
    fun matchesHeyNameAndBareName() {
        assertTrue(WakeWordPhrasePolicy.matches("hey iris", "hey iris"))
        assertTrue(WakeWordPhrasePolicy.matches("iris", "hey iris"))
        assertFalse(WakeWordPhrasePolicy.matches("assistant", "hey assistant"))
    }
}

package com.tcs.vehicleassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTranscriptPrefixTest {

    @Test
    fun emptyOrZeroReturnsBlank() {
        assertEquals("", spokenTranscriptPrefix("", 4))
        assertEquals("", spokenTranscriptPrefix("hello world", 0))
        assertEquals("", spokenTranscriptPrefix("hello world", -1))
    }

    @Test
    fun completesCurrentWord() {
        assertEquals("hello", spokenTranscriptPrefix("hello world", 1))
        assertEquals("hello", spokenTranscriptPrefix("hello world", 5))
        assertEquals("hello world", spokenTranscriptPrefix("hello world", 7))
        assertEquals("hello world", spokenTranscriptPrefix("hello world", 11))
    }

    @Test
    fun fullTextWhenPastEnd() {
        assertEquals("On it", spokenTranscriptPrefix("On it", 99))
    }
}

package com.assistant.ui.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTagParserTest {
    @Test
    fun parsesBlockMoodAndStripsFromText() {
        val parsed = MoodTagParser.parse("<mood>triumph</mood> Yes! Nice work.")
        assertTrue(parsed.found)
        assertEquals(AssistantMoodId.Triumph, parsed.mood)
        assertEquals("Yes! Nice work.", parsed.cleanedText)
    }

    @Test
    fun parsesSelfClosingIdAttr() {
        val parsed = MoodTagParser.parse("""<mood id="concerned"/> Want coffee?""")
        assertTrue(parsed.found)
        assertEquals(AssistantMoodId.Concerned, parsed.mood)
        assertEquals("Want coffee?", parsed.cleanedText)
    }

    @Test
    fun clearTagYieldsNullMood() {
        val parsed = MoodTagParser.parse("<mood/> Hello.")
        assertTrue(parsed.found)
        assertNull(parsed.mood)
        assertEquals("Hello.", parsed.cleanedText)
    }

    @Test
    fun noTagLeavesText() {
        val parsed = MoodTagParser.parse("Just chatting.")
        assertFalse(parsed.found)
        assertNull(parsed.mood)
        assertEquals("Just chatting.", parsed.cleanedText)
    }
}

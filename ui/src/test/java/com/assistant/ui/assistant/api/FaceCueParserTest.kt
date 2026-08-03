package com.assistant.ui.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCueParserTest {

    @Test
    fun parse_fullSlots() {
        val raw = """
            <face left_eye="sunny" right_eye="rain" mouth="music" left_accent="sparkle" right_accent="star"/>
            It looks sunny with a chance of rain.
        """.trimIndent()
        val result = FaceCueParser.parse(raw)
        assertTrue(result.found)
        assertEquals(AssistantFaceCueIcon.Sunny, result.cues?.leftEye)
        assertEquals(AssistantFaceCueIcon.Rain, result.cues?.rightEye)
        assertEquals(AssistantFaceCueIcon.Music, result.cues?.mouth)
        assertEquals(AssistantFaceCueIcon.Sparkle, result.cues?.leftAccent)
        assertEquals(AssistantFaceCueIcon.Star, result.cues?.rightAccent)
        assertFalse(result.cleanedText.contains("<face"))
        assertTrue(result.cleanedText.contains("sunny"))
    }

    @Test
    fun parse_noneClearsSlot() {
        val result = FaceCueParser.parse(
            """<face left_eye="sunny" right_eye="none" mouth="none"/>Hello""",
        )
        assertEquals(AssistantFaceCueIcon.Sunny, result.cues?.leftEye)
        assertNull(result.cues?.rightEye)
        assertNull(result.cues?.mouth)
        assertEquals("Hello", result.cleanedText)
    }

    @Test
    fun parse_emptyTagClearsAll() {
        val result = FaceCueParser.parse("<face/> Listening…")
        assertTrue(result.found)
        assertTrue(result.cues?.isEmpty == true)
        assertEquals("Listening…", result.cleanedText)
    }

    @Test
    fun parse_noTag() {
        val result = FaceCueParser.parse("Just a reply.")
        assertFalse(result.found)
        assertNull(result.cues)
        assertEquals("Just a reply.", result.cleanedText)
    }

    @Test
    fun icon_parse_aliases() {
        assertEquals(AssistantFaceCueIcon.Storm, AssistantFaceCueIcon.parse("storm"))
        assertNull(AssistantFaceCueIcon.parse("none"))
        assertNull(AssistantFaceCueIcon.parse("bogus"))
    }
}

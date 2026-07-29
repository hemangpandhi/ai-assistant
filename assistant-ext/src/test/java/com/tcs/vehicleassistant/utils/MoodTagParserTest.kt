package com.tcs.vehicleassistant.utils

import com.assistant.ui.assistant.api.AssistantMoodId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodTagParserTest {

    @Test
    fun extractAffectiveMood_happy() {
        val text = "Done — cabin is cooler. <MOOD>happy</MOOD>"
        assertEquals(AssistantMoodId.Happy, MoodTagParser.extractAffectiveMood(text))
    }

    @Test
    fun extractAffectiveMood_ignoresPipelineNames() {
        assertNull(MoodTagParser.extractAffectiveMood("ok <MOOD>listening</MOOD>"))
        assertNull(MoodTagParser.extractAffectiveMood("ok <MOOD>thinking</MOOD>"))
        assertNull(MoodTagParser.extractAffectiveMood("ok <MOOD>speaking</MOOD>"))
    }

    @Test
    fun stripMoodTags_removesTag() {
        val cleaned = MoodTagParser.stripMoodTags("All set! <MOOD>excited</MOOD>")
        assertEquals("All set!", cleaned)
    }

    @Test
    fun heuristicForTool_acOn() {
        assertEquals(
            AssistantMoodId.Happy,
            MoodTagParser.heuristicForTool("turnOnAC()", "turn on the ac"),
        )
    }

    @Test
    fun heuristicForTool_drowsy() {
        assertEquals(
            AssistantMoodId.Excited,
            MoodTagParser.heuristicForTool("handleDrowsyDriving()", "falling asleep"),
        )
    }
}

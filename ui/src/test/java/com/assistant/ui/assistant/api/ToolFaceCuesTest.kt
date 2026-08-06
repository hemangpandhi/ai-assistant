package com.assistant.ui.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolFaceCuesTest {

    @Test
    fun forIconId_musicKeepsGeometricEyesUsesCheeks() {
        val cues = ToolFaceCues.forIconId("music")
        assertNull(cues?.leftEye)
        assertNull(cues?.rightEye)
        assertNull(cues?.mouth)
        assertEquals(AssistantFaceCueIcon.Music, cues?.leftAccent)
        assertEquals(AssistantFaceCueIcon.Music, cues?.rightAccent)
    }

    @Test
    fun forIconId_searchDoesNotCoverEyes() {
        val cues = ToolFaceCues.forIconId("search")
        assertNull(cues?.leftEye)
        assertNull(cues?.rightEye)
        assertEquals(AssistantFaceCueIcon.Search, cues?.mouth)
    }

    @Test
    fun forIconId_navigateIsCenteredMap() {
        val cues = ToolFaceCues.forIconId("navigate")
        assertNull(cues?.leftEye)
        assertNull(cues?.rightEye)
        assertEquals(AssistantFaceCueIcon.Navigate, cues?.mouth)
        assertNull(cues?.leftAccent)
        assertNull(cues?.rightAccent)
    }

    @Test
    fun fromSpokenText_musicAndNav() {
        val music = ToolFaceCues.fromSpokenText(
            "Great choice — putting on arijit singh for you!",
        )
        assertEquals(AssistantFaceCueIcon.Music, music?.leftAccent)
        assertNull(music?.mouth)

        val nav = ToolFaceCues.fromSpokenText(
            "Getting you on the road to work — hang tight.",
        )
        assertEquals(AssistantFaceCueIcon.Navigate, nav?.mouth)

        assertNull(ToolFaceCues.fromSpokenText("Okay — I won't do that."))
    }

    @Test
    fun forIconId_stillSupportsWeather() {
        val sunny = ToolFaceCues.forIconId("sunny")
        assertEquals(AssistantFaceCueIcon.Sunny, sunny?.leftEye)
        assertEquals(AssistantFaceCueIcon.Cloudy, sunny?.mouth)
    }

    @Test
    fun forIconId_climateAndIslandBadge() {
        val climate = ToolFaceCues.forIconId("thermostat")
        assertEquals(AssistantFaceCueIcon.Thermostat, climate?.mouth)
        assertEquals(AssistantFaceCueIcon.Thermostat, climate?.islandStatusIcon())

        val heat = ToolFaceCues.forIconId("heat")
        assertEquals(AssistantFaceCueIcon.Heat, heat?.islandStatusIcon())

        val music = ToolFaceCues.music()
        assertEquals(AssistantFaceCueIcon.Music, music.islandStatusIcon())
    }
}

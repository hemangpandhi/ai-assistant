package com.assistant.ui.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherFaceCuesTest {

    @Test
    fun forIconId_matchesAdbWeatherPresets() {
        val sunny = WeatherFaceCues.forIconId("sunny")
        assertEquals(AssistantFaceCueIcon.Sunny, sunny?.leftEye)
        assertEquals(AssistantFaceCueIcon.Sunny, sunny?.rightEye)
        assertEquals(AssistantFaceCueIcon.Cloudy, sunny?.mouth)

        val rain = WeatherFaceCues.forIconId("rain")
        assertEquals(AssistantFaceCueIcon.Rain, rain?.leftEye)
        assertEquals(AssistantFaceCueIcon.Storm, rain?.mouth)
    }

    @Test
    fun fromSpokenText_weatherReply() {
        val cues = WeatherFaceCues.fromSpokenText(
            "The current weather in Tokyo is thunderstorms, about 68 degrees Fahrenheit.",
        )
        assertEquals(AssistantFaceCueIcon.Storm, cues?.leftEye)
        assertNull(WeatherFaceCues.fromSpokenText("Got it, I've remembered that."))
    }
}

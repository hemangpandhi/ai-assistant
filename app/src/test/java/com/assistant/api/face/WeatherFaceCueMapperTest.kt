package com.assistant.api.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherFaceCueMapperTest {

    @Test
    fun wmo_mapsToWeatherIcons() {
        assertEquals("sunny", WeatherFaceCueMapper.iconIdForWmo(0))
        assertEquals("sunny", WeatherFaceCueMapper.iconIdForWmo(1))
        assertEquals("cloudy", WeatherFaceCueMapper.iconIdForWmo(3))
        assertEquals("rain", WeatherFaceCueMapper.iconIdForWmo(61))
        assertEquals("snow", WeatherFaceCueMapper.iconIdForWmo(71))
        assertEquals("storm", WeatherFaceCueMapper.iconIdForWmo(95))
        assertNull(WeatherFaceCueMapper.iconIdForWmo(999))
    }

    @Test
    fun condition_mapsPhrases() {
        assertEquals("sunny", WeatherFaceCueMapper.iconIdForCondition("mainly clear"))
        assertEquals("cloudy", WeatherFaceCueMapper.iconIdForCondition("partly cloudy"))
        assertEquals("rain", WeatherFaceCueMapper.iconIdForCondition("rain showers"))
        assertEquals("storm", WeatherFaceCueMapper.iconIdForCondition("thunderstorms with hail"))
        assertEquals("snow", WeatherFaceCueMapper.iconIdForCondition("snow grains"))
    }

    @Test
    fun spokenText_infersFromDirectToolFormat() {
        val spoken =
            "The current weather in Tokyo is rain, about 55 degrees Fahrenheit, humidity 80 percent."
        assertEquals("rain", WeatherFaceCueMapper.iconIdFromSpokenText(spoken))
        assertEquals(
            "sunny",
            WeatherFaceCueMapper.iconIdFromSpokenText(
                "The current weather in your area is mainly clear, about 72 degrees Fahrenheit.",
            ),
        )
        assertNull(WeatherFaceCueMapper.iconIdFromSpokenText("Opening the climate screen."))
    }

    @Test
    fun faceTag_usesCatalogIds() {
        val tag = WeatherFaceCueMapper.faceTag("sunny")
        assertTrue(tag.contains("""left_eye="sunny""""))
        assertTrue(tag.contains("""mouth="cloudy""""))
        assertEquals("", WeatherFaceCueMapper.faceTag("bogus"))
    }
}

package com.tcs.vehicleassistant.requirements

import com.tcs.vehicleassistant.core.DirectToolResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks overnight gap-fill: documented phrases that previously fell through (weather, identity,
 * nearby, news) must DirectTool-resolve after registry + CITY/AMENITY support.
 */
class RequirementsGapFillUnitTest {

    private val specs get() = RegistryFixture.toSpecs()

    private fun hit(query: String): DirectToolResolver.Hit {
        val outcome = DirectToolResolver.resolve(query, specs)
        assertTrue("expected Execute for '$query', got $outcome", outcome is DirectToolResolver.Outcome.Execute)
        return (outcome as DirectToolResolver.Outcome.Execute).hit
    }

    @Test
    fun whatIsTheWeather_directTools() {
        for (q in listOf(
            "what is the weather",
            "What's the weather?",
            "whats the weather",
            "weather",
            "weather in Tokyo",
            "weather forecast",
        )) {
            val h = hit(q)
            assertEquals(q, "getWeather", h.toolId)
            assertTrue(h.toolCall.startsWith("getWeather(", ignoreCase = true))
        }
        assertTrue(hit("weather in Tokyo").toolCall.contains("Tokyo", ignoreCase = true))
        assertTrue(hit("what is the weather").toolCall.contains("here", ignoreCase = true))
    }

    @Test
    fun jokeAboutWeather_stillFallsThrough() {
        val outcome = DirectToolResolver.resolve("tell me a joke about the weather", specs)
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun vehicleIdentity_directTools() {
        for (q in listOf("what model is this", "what car is this", "who are you", "what am I driving")) {
            assertEquals(q, "answerVehicleIdentity", hit(q).toolId)
        }
    }

    @Test
    fun nearbyAndNews_directTools() {
        assertEquals("searchNearby", hit("find nearby gas").toolId)
        assertTrue(hit("gas station").toolCall.contains("gas", ignoreCase = true))
        assertTrue(hit("nearby pizza").toolCall.contains("pizza", ignoreCase = true))
        assertTrue(hit("I am hungry").toolCall.contains("restaurant", ignoreCase = true))
        assertEquals("getNewsHighlights", hit("latest news").toolId)
        assertEquals("suggestNearbyPlaces", hit("suggest nearby places").toolId)
    }

    @Test
    fun defrosterAndWarmSeat_docTriggers() {
        assertEquals("turnOffDefroster", hit("turn off front defroster").toolId)
        assertEquals("setSeatHeater", hit("warm seat").toolId)
    }
}

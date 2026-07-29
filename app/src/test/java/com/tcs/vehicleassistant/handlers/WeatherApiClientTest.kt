package com.tcs.vehicleassistant.handlers

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherApiClientTest {

    @Test
    fun parseCurrent_formatsSpokenSummary() {
        val json = """
            {
              "current": {
                "temperature_2m": 72.4,
                "relative_humidity_2m": 55,
                "weather_code": 1,
                "wind_speed_10m": 8.2
              }
            }
        """.trimIndent()
        val weather = WeatherApiClient.parseCurrent(json, "Tokyo")
        assertNotNull(weather)
        assertEquals(72, weather!!.tempF)
        assertEquals(55, weather.humidityPct)
        assertEquals(8, weather.windMph)
        assertEquals("mainly clear", weather.condition)
        val spoken = WeatherApiClient.formatSpoken(weather)
        assertTrue(spoken, spoken.contains("Tokyo"))
        assertTrue(spoken, spoken.contains("mainly clear"))
        assertTrue(spoken, spoken.contains("72"))
        assertTrue(spoken, spoken.startsWith("The current weather"))
        assertTrue(spoken, !spoken.contains("opened", ignoreCase = true))
    }

    @Test
    fun wmoCondition_knownCodes() {
        assertEquals("clear", WeatherApiClient.wmoCondition(0))
        assertEquals("rain", WeatherApiClient.wmoCondition(61))
        assertEquals("thunderstorms", WeatherApiClient.wmoCondition(95))
    }

    @Test
    fun resolveLocation_usesFallbackCoordsForHere() = runBlocking {
        val point = WeatherApiClient.resolveLocation(
            cityOrHere = "here",
            fallbackLat = 35.68,
            fallbackLon = 139.76,
            fallbackLabel = "Tokyo",
        )
        assertNotNull(point)
        assertEquals(35.68, point!!.latitude, 0.001)
        assertEquals(139.76, point.longitude, 0.001)
        assertEquals("Tokyo", point.label)
    }

    @Test
    fun pickBestGeocodeResult_prefersAdmin1MatchOverForeignNamesake() {
        val results = JSONArray("""
            [
              {
                "name": "Gujarat",
                "latitude": 27.04554,
                "longitude": 86.25235,
                "feature_code": "PPLL",
                "country_code": "NP",
                "admin1": "Bagmati Province"
              },
              {
                "name": "GIFT City",
                "latitude": 23.15963,
                "longitude": 72.68451,
                "feature_code": "PPL",
                "country_code": "IN",
                "admin1": "Gujarat"
              }
            ]
        """.trimIndent())
        val point = WeatherApiClient.pickBestGeocodeResult("Gujarat", results)
        assertNotNull(point)
        assertEquals(23.15963, point!!.latitude, 0.001)
        assertEquals(72.68451, point.longitude, 0.001)
        assertTrue(point.label, point.label.contains("Gujarat", ignoreCase = true))
        assertTrue(point.label, !point.label.contains("Bagmati", ignoreCase = true))
    }

    @Test
    fun liveOpenMeteo_tokyo_returnsSpokenFacts() = runBlocking {
        val point = WeatherApiClient.geocode("Tokyo")
        assertNotNull("geocode Tokyo", point)
        val weather = WeatherApiClient.fetchCurrent(point!!)
        assertNotNull("fetch Tokyo weather", weather)
        val spoken = WeatherApiClient.formatSpoken(weather!!)
        assertTrue(spoken, spoken.contains("current weather", ignoreCase = true))
        assertTrue(spoken, spoken.contains("degrees Fahrenheit"))
        assertTrue(spoken, !spoken.contains("couldn't reach", ignoreCase = true))
    }

    @Test
    fun liveGeocode_gujarat_resolvesInIndiaNotNepal() = runBlocking {
        val detailed = WeatherApiClient.geocodeDetailed("Gujarat")
        assertTrue("expected Ok, got $detailed", detailed is WeatherApiClient.LookupResult.Ok)
        val point = (detailed as WeatherApiClient.LookupResult.Ok).value
        // Indian Gujarat centroid/city band roughly 20–25N, 68–75E; Nepal namesake ~27N,86E
        assertTrue("lat=${point.latitude}", point.latitude in 20.0..25.5)
        assertTrue("lon=${point.longitude}", point.longitude in 68.0..75.5)
        assertTrue(point.label, point.label.contains("Gujarat", ignoreCase = true))
        val weather = WeatherApiClient.fetchCurrent(point)
        assertNotNull("fetch Gujarat weather", weather)
        val spoken = WeatherApiClient.formatSpoken(weather!!)
        assertTrue(spoken, spoken.contains("degrees Fahrenheit"))
    }

    @Test
    fun liveGeocode_maharashtra_orCalifornia_stateLevel() = runBlocking {
        // Maharashtra often missing from Open-Meteo place index — Nominatim fallback should rescue.
        val mh = WeatherApiClient.geocodeDetailed("Maharashtra")
        assertTrue("Maharashtra expected Ok, got $mh", mh is WeatherApiClient.LookupResult.Ok)
        val mhPoint = (mh as WeatherApiClient.LookupResult.Ok).value
        assertTrue("MH lat=${mhPoint.latitude}", mhPoint.latitude in 15.0..22.5)
        assertTrue("MH lon=${mhPoint.longitude}", mhPoint.longitude in 72.0..81.0)

        val ca = WeatherApiClient.geocodeDetailed("California")
        // May resolve to a US city named California OR Nominatim state; just ensure not null + fetch works.
        assertTrue("California expected Ok, got $ca", ca is WeatherApiClient.LookupResult.Ok)
        val weather = WeatherApiClient.fetchCurrent((ca as WeatherApiClient.LookupResult.Ok).value)
        assertNotNull(weather)
    }
}

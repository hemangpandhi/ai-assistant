package com.tcs.vehicleassistant.handlers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Live weather via Open-Meteo (no API key). Returns spoken facts only — never invents values.
 * All network work runs on [Dispatchers.IO] (DirectTool otherwise executes on Main).
 */
object WeatherApiClient {

    private const val TAG = "WeatherApiClient"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class GeoPoint(val latitude: Double, val longitude: Double, val label: String)

    data class CurrentWeather(
        val locationLabel: String,
        val tempF: Int,
        val humidityPct: Int?,
        val windMph: Int?,
        val condition: String,
        val weatherCode: Int,
    )

    /** Distinguishes empty geocode hits from transport/HTTP failures for spoken feedback. */
    sealed class LookupResult<out T> {
        data class Ok<T>(val value: T) : LookupResult<T>()
        data class NotFound(val query: String) : LookupResult<Nothing>()
        data class NetworkError(val stage: String, val detail: String?) : LookupResult<Nothing>()
    }

    suspend fun resolveLocation(
        cityOrHere: String,
        fallbackLat: Double?,
        fallbackLon: Double?,
        fallbackLabel: String,
    ): GeoPoint? = when (val r = resolveLocationDetailed(cityOrHere, fallbackLat, fallbackLon, fallbackLabel)) {
        is LookupResult.Ok -> r.value
        else -> null
    }

    suspend fun resolveLocationDetailed(
        cityOrHere: String,
        fallbackLat: Double?,
        fallbackLon: Double?,
        fallbackLabel: String,
    ): LookupResult<GeoPoint> {
        val cleaned = cityOrHere.trim().removeSurrounding("\"")
        if (cleaned.isBlank() ||
            cleaned.equals("CITY", ignoreCase = true) ||
            cleaned.equals("here", ignoreCase = true) ||
            cleaned.equals("current", ignoreCase = true) ||
            cleaned.equals("your area", ignoreCase = true)
        ) {
            if (fallbackLat != null && fallbackLon != null &&
                !fallbackLat.isNaN() && !fallbackLon.isNaN()
            ) {
                return LookupResult.Ok(
                    GeoPoint(fallbackLat, fallbackLon, fallbackLabel.ifBlank { "your area" }),
                )
            }
            val label = fallbackLabel.takeIf { it.isNotBlank() && !it.equals("your area", true) }
                ?: return LookupResult.NotFound(cleaned.ifBlank { "here" })
            return geocodeDetailed(label)
        }
        return geocodeDetailed(cleaned)
    }

    suspend fun geocode(city: String): GeoPoint? = when (val r = geocodeDetailed(city)) {
        is LookupResult.Ok -> r.value
        else -> null
    }

    suspend fun geocodeDetailed(city: String): LookupResult<GeoPoint> = withContext(Dispatchers.IO) {
        val q = city.trim()
        if (q.isBlank()) return@withContext LookupResult.NotFound(q)

        when (val openMeteo = geocodeOpenMeteo(q)) {
            is LookupResult.Ok -> return@withContext openMeteo
            is LookupResult.NotFound -> {
                Log.i(TAG, "Open-Meteo geocode empty for '$q'; trying Nominatim admin fallback")
                return@withContext geocodeNominatim(q)
            }
            is LookupResult.NetworkError -> {
                // Still try Nominatim once; if that also fails, prefer the first network error detail.
                Log.w(TAG, "Open-Meteo geocode network error for '$q': ${openMeteo.detail}")
                return@withContext when (val nominatim = geocodeNominatim(q)) {
                    is LookupResult.Ok -> nominatim
                    is LookupResult.NotFound -> openMeteo
                    is LookupResult.NetworkError -> openMeteo
                }
            }
        }
    }

    /**
     * Ranks Open-Meteo hits so state/admin queries (e.g. Gujarat) prefer ADM* or
     * admin1-name matches over obscure foreign localities that share the same name.
     */
    fun pickBestGeocodeResult(query: String, results: JSONArray): GeoPoint? {
        if (results.length() == 0) return null
        val q = query.trim()
        var bestIdx = 0
        var bestScore = Int.MIN_VALUE
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val score = scoreGeocodeResult(q, obj)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return geoPointFromOpenMeteo(results.getJSONObject(bestIdx), q)
    }

    fun scoreGeocodeResult(query: String, obj: JSONObject): Int {
        val q = query.trim().lowercase()
        val name = obj.optString("name").lowercase()
        val admin1 = obj.optString("admin1").lowercase()
        val feature = obj.optString("feature_code")
        val country = obj.optString("country_code")
        val population = obj.optInt("population", 0)
        var score = 0
        val nameExact = name == q
        val admin1Exact = admin1 == q
        when {
            nameExact && feature.startsWith("ADM1") -> score += 1000
            nameExact && feature.startsWith("ADM2") -> score += 900
            nameExact && feature.startsWith("ADM") -> score += 850
            admin1Exact && !nameExact -> score += 800 // city inside requested state
            nameExact && feature in setOf("PPLC", "PPLA", "PPLA2", "PPLS") -> score += 700
            nameExact && feature == "PPL" -> score += 500
            nameExact && feature == "PPLL" -> score += 150 // tiny locality — demote
            nameExact -> score += 400
            name.contains(q) || q.contains(name) -> score += 100
        }
        // Mild preference for India/US when the query looks like a well-known state name
        // and we matched via admin1 (state container) rather than a foreign namesake.
        if (admin1Exact && country == "IN") score += 40
        if (admin1Exact && country == "US") score += 20
        if (population > 0) score += population.coerceAtMost(5_000_000) / 100_000
        return score
    }

    private fun geoPointFromOpenMeteo(first: JSONObject, query: String): GeoPoint? {
        if (!first.has("latitude") || !first.has("longitude")) return null
        val name = first.optString("name").ifBlank { query }
        val admin = first.optString("admin1").takeIf { it.isNotBlank() }
        val country = first.optString("country_code").takeIf { it.isNotBlank() }
        val feature = first.optString("feature_code")
        val label = when {
            // Prefer speaking the state the user asked for when we matched via admin1.
            admin != null && admin.equals(query.trim(), ignoreCase = true) &&
                !name.equals(query.trim(), ignoreCase = true) ->
                listOfNotNull(admin, country).joinToString(", ")
            feature.startsWith("ADM") ->
                listOfNotNull(name, country).joinToString(", ")
            else ->
                listOfNotNull(name, admin, country).joinToString(", ")
        }
        return GeoPoint(
            latitude = first.getDouble("latitude"),
            longitude = first.getDouble("longitude"),
            label = label,
        )
    }

    private fun geocodeOpenMeteo(q: String): LookupResult<GeoPoint> {
        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url =
                "https://geocoding-api.open-meteo.com/v1/search?name=$encoded" +
                    "&count=10&language=en&format=json"
            val http = httpGetDetailed(url, "geocode")
            val body = when (http) {
                is LookupResult.Ok -> http.value
                is LookupResult.NotFound -> return LookupResult.NotFound(q)
                is LookupResult.NetworkError -> return http
            }
            val results = JSONObject(body).optJSONArray("results")
            if (results == null || results.length() == 0) {
                Log.i(TAG, "Open-Meteo returned 0 results for '$q'")
                return LookupResult.NotFound(q)
            }
            val point = pickBestGeocodeResult(q, results)
                ?: return LookupResult.NotFound(q)
            Log.i(TAG, "Open-Meteo geocode '$q' -> ${point.label} (${point.latitude},${point.longitude})")
            LookupResult.Ok(point)
        } catch (e: Exception) {
            Log.w(TAG, "Geocode Open-Meteo failed for $q", e)
            LookupResult.NetworkError("geocode", e.message)
        }
    }

    private fun geocodeNominatim(q: String): LookupResult<GeoPoint> {
        return try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url =
                "https://nominatim.openstreetmap.org/search?q=$encoded" +
                    "&format=json&limit=3&addressdetails=1"
            val http = httpGetDetailed(url, "geocode-nominatim")
            val body = when (http) {
                is LookupResult.Ok -> http.value
                is LookupResult.NotFound -> return LookupResult.NotFound(q)
                is LookupResult.NetworkError -> return http
            }
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                Log.i(TAG, "Nominatim returned 0 results for '$q'")
                return LookupResult.NotFound(q)
            }
            // Prefer administrative/state boundaries.
            var chosen: JSONObject? = null
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = obj.optString("type")
                val cls = obj.optString("class")
                if (cls == "boundary" || type == "administrative" || type == "state") {
                    chosen = obj
                    break
                }
            }
            if (chosen == null) chosen = arr.getJSONObject(0)
            val lat = chosen.getString("lat").toDoubleOrNull()
            val lon = chosen.getString("lon").toDoubleOrNull()
            if (lat == null || lon == null) return LookupResult.NotFound(q)
            val display = chosen.optString("display_name")
            val shortLabel = display.split(',').take(2).joinToString(",").trim()
                .ifBlank { q }
            val point = GeoPoint(lat, lon, shortLabel)
            Log.i(TAG, "Nominatim geocode '$q' -> ${point.label} (${point.latitude},${point.longitude})")
            LookupResult.Ok(point)
        } catch (e: Exception) {
            Log.w(TAG, "Geocode Nominatim failed for $q", e)
            LookupResult.NetworkError("geocode-nominatim", e.message)
        }
    }

    suspend fun fetchCurrent(point: GeoPoint): CurrentWeather? =
        when (val r = fetchCurrentDetailed(point)) {
            is LookupResult.Ok -> r.value
            else -> null
        }

    suspend fun fetchCurrentDetailed(point: GeoPoint): LookupResult<CurrentWeather> =
        withContext(Dispatchers.IO) {
            try {
                val url =
                    "https://api.open-meteo.com/v1/forecast?" +
                        "latitude=${point.latitude}&longitude=${point.longitude}" +
                        "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                        "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto"
                val http = httpGetDetailed(url, "forecast")
                val body = when (http) {
                    is LookupResult.Ok -> http.value
                    is LookupResult.NotFound ->
                        return@withContext LookupResult.NetworkError("forecast", "empty body")
                    is LookupResult.NetworkError -> return@withContext http
                }
                val parsed = parseCurrent(body, point.label)
                    ?: return@withContext LookupResult.NetworkError("forecast", "parse failed")
                LookupResult.Ok(parsed)
            } catch (e: Exception) {
                Log.w(TAG, "Weather fetch failed for ${point.label}", e)
                LookupResult.NetworkError("forecast", e.message)
            }
        }

    fun parseCurrent(jsonBody: String, locationLabel: String): CurrentWeather? {
        return try {
            val root = JSONObject(jsonBody)
            val current = root.optJSONObject("current") ?: return null
            if (!current.has("temperature_2m") || !current.has("weather_code")) return null
            val temp = current.getDouble("temperature_2m").roundToInt()
            val code = current.getInt("weather_code")
            val humidity = if (current.has("relative_humidity_2m")) {
                current.getInt("relative_humidity_2m")
            } else {
                null
            }
            val wind = if (current.has("wind_speed_10m")) {
                current.getDouble("wind_speed_10m").roundToInt()
            } else {
                null
            }
            CurrentWeather(
                locationLabel = locationLabel,
                tempF = temp,
                humidityPct = humidity,
                windMph = wind,
                condition = wmoCondition(code),
                weatherCode = code,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Weather parse failed", e)
            null
        }
    }

    fun formatSpoken(weather: CurrentWeather): String {
        val where = weather.locationLabel.ifBlank { "your area" }
        val parts = mutableListOf(
            "The current weather in $where is ${weather.condition}, about ${weather.tempF} degrees Fahrenheit",
        )
        weather.humidityPct?.let { parts += "humidity $it percent" }
        weather.windMph?.let { parts += "wind around $it miles per hour" }
        return parts.joinToString(", ") + "."
    }

    /** WMO Weather interpretation codes (Open-Meteo). */
    fun wmoCondition(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75 -> "snow"
        77 -> "snow grains"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "mixed conditions"
    }

    private fun httpGetDetailed(url: String, stage: String): LookupResult<String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "VehicleAssistant/1.0 (Android; weather; contact: vehicle-assistant)")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP $code at $stage for $url body=${body?.take(200)}")
                    return LookupResult.NetworkError(stage, "HTTP $code")
                }
                if (body.isNullOrBlank()) {
                    Log.w(TAG, "HTTP $code empty body at $stage for $url")
                    return LookupResult.NetworkError(stage, "empty body")
                }
                LookupResult.Ok(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP failure at $stage for $url", e)
            LookupResult.NetworkError(stage, e.message)
        }
    }
}

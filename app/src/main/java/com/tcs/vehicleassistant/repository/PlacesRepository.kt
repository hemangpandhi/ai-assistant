package com.tcs.vehicleassistant.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PlacesRepository {

    suspend fun searchNearby(amenity: String, bbox: String): List<String> = withContext(Dispatchers.IO) {
        val lowerAmenity = amenity.lowercase().trim()
        var overpassQuery = "node[\"amenity\"~\"$lowerAmenity\",i]"
        if (lowerAmenity.contains("italian")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"italian\",i]" }
        else if (lowerAmenity.contains("mexican")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"mexican\",i]" }
        else if (lowerAmenity.contains("chinese")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"chinese\",i]" }
        else if (lowerAmenity.contains("pizza")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"pizza\",i]" }
        else if (lowerAmenity.contains("burger") || lowerAmenity.contains("fast food") || lowerAmenity.contains("american")) { overpassQuery = "node[\"amenity\"=\"fast_food\"][\"cuisine\"~\"burger|american\",i]" }
        else if (lowerAmenity.contains("sushi") || lowerAmenity.contains("japanese")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"japanese|sushi\",i]" }
        else if (lowerAmenity.contains("indian")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"indian\",i]" }
        else if (lowerAmenity.contains("thai")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"thai\",i]" }
        else if (lowerAmenity.contains("gas") || lowerAmenity.contains("fuel")) { overpassQuery = "node[\"amenity\"=\"fuel\"]" }
        else if (lowerAmenity.contains("charging")) { overpassQuery = "node[\"amenity\"=\"charging_station\"]" }
        else if (lowerAmenity.contains("cof") || lowerAmenity.contains("cafe")) { overpassQuery = "node[\"amenity\"=\"cafe\"]" }
        else if (lowerAmenity.contains("food") || lowerAmenity.contains("restaurant")) { overpassQuery = "node[\"amenity\"=\"restaurant\"]" }

        val fullQuery = "[out:json][timeout:10];$overpassQuery($bbox);out 10;"
        val elements = fetchOverpassElements(fullQuery) ?: return@withContext emptyList()
        
        val places = mutableListOf<String>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            var name = tags.optString("name", tags.optString("name:en", "")).trim()
            val brand = tags.optString("brand", tags.optString("brand:en", "")).trim()
            if (name.isEmpty() && brand.isNotEmpty()) name = brand
            if (name.isNotEmpty() && !places.contains(name)) {
                places.add(name)
                if (places.size >= 3) break
            }
        }
        
        if (places.isEmpty() && elements.length() > 0) {
            places.add("Local $lowerAmenity")
        }
        
        places
    }

    suspend fun suggestNearbyAttractions(bbox: String): List<String> = withContext(Dispatchers.IO) {
        val overpassQuery = "node[\"tourism\"~\"attraction|museum|viewpoint|gallery\",i]"
        val fullQuery = "[out:json][timeout:10];($overpassQuery($bbox);node[\"amenity\"=\"place_of_worship\"]($bbox););out 10;"
        
        val elements = fetchOverpassElements(fullQuery) ?: return@withContext emptyList()
        
        val places = mutableListOf<String>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name", tags.optString("name:en", "")).trim()
            if (name.isNotEmpty() && !places.contains(name)) {
                places.add(name)
                if (places.size >= 3) break
            }
        }
        places
    }

    private fun fetchOverpassElements(fullQuery: String): org.json.JSONArray? {
        try {
            val encodedQuery = URLEncoder.encode(fullQuery, "UTF-8")
            val url = URL("https://overpass-api.de/api/interpreter?data=$encodedQuery")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "GeminiNanoSample/1.0")
            connection.requestMethod = "GET"
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(response)
                return jsonObj.optJSONArray("elements")
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }
}

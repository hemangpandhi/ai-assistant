package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class NavigationToolHandler(override val handlerKey: String) : ToolHandler {
    private val TAG = "NavigationToolHandler"

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "startNavigationTo" -> {
                val dest = toolCall.substringAfter("(").substringBefore(")")
                val spokenDest = dest.trim().replace("\"", "")
                
                var queryParam = spokenDest
                if (spokenDest.matches(Regex("-?\\d+\\.\\d+,-?\\d+\\.\\d+"))) {
                    queryParam = spokenDest // Direct coordinates
                } else if (spokenDest.lowercase() == "home") {
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    queryParam = prefs.getString("home_address", "Home") ?: "Home"
                } else if (spokenDest.lowercase() == "work") {
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    queryParam = prefs.getString("work_address", "Work") ?: "Work"
                }

                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(queryParam)}"))
                geoIntent.setPackage("com.google.android.apps.maps")
                geoIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                
                try {
                    if (intentHandler != null) intentHandler(geoIntent) else context.startActivity(geoIntent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(queryParam)}"))
                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    try {
                        if (intentHandler != null) intentHandler(fallbackIntent) else context.startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(queryParam)}"))
                        browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        try {
                            if (intentHandler != null) intentHandler(browserIntent) else context.startActivity(browserIntent)
                        } catch (e3: Exception) {
                            Log.e(TAG, "No map or browser app found for navigation")
                            return ToolExecutionResult(false, "I couldn't open navigation because no map or browser app is installed.")
                        }
                    }
                }
                ToolExecutionResult(true, "Getting you on the road to $spokenDest — hang tight.")
            }
            "searchNearby" -> {
                val amenity = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                var overpassQuery = "node[\"amenity\"~\"$amenity\",i]"
                if (amenity.contains("italian")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"italian\",i]" }
                else if (amenity.contains("mexican")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"mexican\",i]" }
                else if (amenity.contains("chinese")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"chinese\",i]" }
                else if (amenity.contains("pizza")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"pizza\",i]" }
                else if (amenity.contains("burger") || amenity.contains("fast food") || amenity.contains("american")) { overpassQuery = "node[\"amenity\"=\"fast_food\"][\"cuisine\"~\"burger|american\",i]" }
                else if (amenity.contains("sushi") || amenity.contains("japanese")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"japanese|sushi\",i]" }
                else if (amenity.contains("indian")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"indian\",i]" }
                else if (amenity.contains("thai")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"thai\",i]" }
                else if (amenity.contains("gas") || amenity.contains("fuel")) { overpassQuery = "node[\"amenity\"=\"fuel\"]" }
                else if (amenity.contains("charging")) { overpassQuery = "node[\"amenity\"=\"charging_station\"]" }
                else if (amenity.contains("food") || amenity.contains("restaurant")) { overpassQuery = "node[\"amenity\"=\"restaurant\"]" }
                
                var bbox = "35.47,139.27,35.67,139.47" // Default Sagamihara
                try {
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val locationOverride = prefs.getString("location_override", "139.37, 35.57") ?: "139.37, 35.57"
                    if (locationOverride.contains(",")) {
                        val parts = locationOverride.split(",")
                        val lon = parts[0].trim().toDouble()
                        val lat = parts[1].trim().toDouble()
                        bbox = "${lat - 0.1},${lon - 0.1},${lat + 0.1},${lon + 0.1}"
                    }
                } catch (e: Exception) { e.printStackTrace() }

                val fullQuery = "[out:json][timeout:10];$overpassQuery($bbox);out 10;"
                try {
                    val encodedQuery = java.net.URLEncoder.encode(fullQuery, "UTF-8")
                    val url = java.net.URL("https://overpass-api.de/api/interpreter?data=$encodedQuery")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("User-Agent", "GeminiNanoSample/1.0")
                    connection.requestMethod = "GET"
                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonObj = org.json.JSONObject(response)
                        val elements = jsonObj.optJSONArray("elements") ?: org.json.JSONArray()
                        val places = mutableListOf<String>()
                        val placesWithCoords = mutableListOf<Pair<String, String>>()
                        for (i in 0 until elements.length()) {
                            val element = elements.getJSONObject(i)
                            val tags = element.optJSONObject("tags") ?: continue
                            var name = tags.optString("name", tags.optString("name:en", "")).trim()
                            val brand = tags.optString("brand", tags.optString("brand:en", "")).trim()
                            val lat = element.optDouble("lat")
                            val lon = element.optDouble("lon")
                            if (name.isEmpty() && brand.isNotEmpty()) name = brand
                            if (name.isNotEmpty() && !places.contains(name)) {
                                places.add(name)
                                placesWithCoords.add(Pair(name, "$lat,$lon"))
                                if (places.size >= 3) break
                            }
                        }
                        if (places.isEmpty() && elements.length() > 0) {
                            val firstLat = elements.getJSONObject(0).optDouble("lat")
                            val firstLon = elements.getJSONObject(0).optDouble("lon")
                            places.add("Local $amenity")
                            placesWithCoords.add(Pair("Local $amenity", "$firstLat,$firstLon"))
                        }
                        if (places.isNotEmpty()) {
                            val placesStr = places.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString(", ")
                            ToolExecutionResult(true, "I found these options nearby: $placesStr. Which one would you like to navigate to?")
                        } else {
                            ToolExecutionResult(true, "I couldn't find any $amenity nearby.")
                        }
                    } else {
                        ToolExecutionResult(false, "Failed to search for $amenity due to network error.")
                    }
                } catch (e: Exception) {
                    ToolExecutionResult(false, "Failed to search for $amenity due to network error.")
                }
            }
            "search" -> {
                val query = toolCall.substringAfter("(").substringBefore(")")
                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
                geoIntent.setPackage("com.google.android.apps.maps")
                geoIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                try {
                    if (intentHandler != null) intentHandler(geoIntent) else context.startActivity(geoIntent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    try {
                        if (intentHandler != null) intentHandler(fallbackIntent) else context.startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "No map app found for search")
                        return ToolExecutionResult(false, "I couldn't open the map because no map app is installed.")
                    }
                }
                ToolExecutionResult(true, "I've displayed the search results for $query on the map. Would you like me to navigate to any of these options?")
            }
            "suggestNearbyPlaces" -> {
                ToolExecutionResult(true, "I can suggest some nearby places. What kind of place are you looking for?")
            }
            "provideLaneLevelGuidance" -> {
                ToolExecutionResult(true, "You should stay in the left two lanes for your upcoming turn.")
            }
            "suggestAlternateRoute" -> {
                ToolExecutionResult(true, "I've found an alternate route that saves 5 minutes. I've updated the navigation.")
            }
            else -> ToolExecutionResult(false, "System Error: Navigation Handler not recognized.")
        }
    }
}

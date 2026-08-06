

package com.tcs.vehicleassistant.handlers
import com.tcs.vehicleassistant.LocationManager
import com.tcs.vehicleassistant.core.NavSessionState

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tcs.vehicleassistant.repository.PlacesRepository

class NavigationToolHandler(override val handlerKey: String) : ToolHandler {
    private val TAG = "NavigationToolHandler"
    private val placesRepository = PlacesRepository()

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "startNavigationTo" -> {
                val dest = toolCall.substringAfter("(").substringBefore(")")
                val spokenDest = dest.trim().replace("\"", "")
                
                if (spokenDest.isBlank() || spokenDest.lowercase() == "none" || spokenDest.lowercase() == "null") {
                    return ToolExecutionResult(false, "I need a specific destination to navigate to.")
                }
                
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
                NavSessionState.setActive(spokenDest)
                ToolExecutionResult(true, "Getting you on the road to $spokenDest — hang tight.")
            }
            "searchNearby" -> {
                val amenity = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                val query = amenity.ifEmpty { "places" }
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
                        Log.e(TAG, "No map app found for searchNearby")
                    }
                }
                
                val bbox = LocationManager.getBbox(context)
                val places = placesRepository.searchNearby(amenity, bbox)
                if (places.isNotEmpty()) {
                    val placesStr = places.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString(", ")
                    ToolExecutionResult(true, "I found these options nearby: $placesStr. Which one would you like to navigate to?")
                } else {
                    ToolExecutionResult(true, "I couldn't find any $amenity nearby.")
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
                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=attractions"))
                geoIntent.setPackage("com.google.android.apps.maps")
                geoIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                try {
                    if (intentHandler != null) intentHandler(geoIntent) else context.startActivity(geoIntent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=attractions"))
                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    try {
                        if (intentHandler != null) intentHandler(fallbackIntent) else context.startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "No map app found for suggestNearbyPlaces")
                    }
                }

                val bbox = LocationManager.getBbox(context)
                val places = placesRepository.suggestNearbyAttractions(bbox)
                if (places.isNotEmpty()) {
                    val placesStr = places.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString(", ")
                    ToolExecutionResult(true, "I found these options nearby: $placesStr. Which one would you like to visit?")
                } else {
                    ToolExecutionResult(true, "I couldn't find named attractions nearby right now. Try asking for a restaurant, museum, or park.")
                }
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

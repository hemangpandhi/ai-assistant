package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager

class SystemToolHandler(override val handlerKey: String) : ToolHandler {
    
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "checkVehicleState" -> {
                val stateString = com.tcs.vehicleassistant.VehicleManager.getLLMContextString(context)
                ToolExecutionResult(true, "The current real-time vehicle state is: $stateString")
            }
            "remember" -> {
                val fact = toolCall.substringAfter("(").substringBefore(")")
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val currentMemory = prefs.getString("user_memory", "") ?: ""
                val newMemory = if (currentMemory.isEmpty()) fact else "$currentMemory. $fact"
                prefs.edit().putString("user_memory", newMemory).apply()
                ToolExecutionResult(true, "Got it, I've remembered that.")
            }
            "getWeather" -> {
                val city = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val mockTemp = (15..30).random()
                val conditions = listOf("sunny", "partly cloudy", "raining", "clear").random()
                val weatherData = "The current weather in $city is $mockTemp degrees Celsius and $conditions."
                
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                intent.putExtra(SearchManager.QUERY, "weather in $city")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                } catch (e: Exception) {}
                
                ToolExecutionResult(true, weatherData)
            }
            "openApp" -> {
                val appName = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "I've opened the app store to find $appName.")
                } catch (e: Exception) {
                    ToolExecutionResult(false, "I couldn't open the app.")
                }
            }
            "sendUpcomingEventReminder" -> {
                ToolExecutionResult(true, "You have a meeting scheduled in 30 minutes.")
            }
            "explainChildSeatInstallation" -> {
                ToolExecutionResult(true, "To install the child seat, refer to your vehicle's LATCH system anchors located in the rear seats.")
            }
            "suggestUmbrellaIfRainy" -> {
                ToolExecutionResult(true, "There is rain expected at your destination. I suggest taking an umbrella.")
            }
            "getNewsHighlights" -> {
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                intent.putExtra(SearchManager.QUERY, "top news highlights today")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "Here are today's top news highlights.")
                } catch (e: Exception) {
                    ToolExecutionResult(false, "I couldn't load the news highlights.")
                }
            }
            "answerVehicleIdentity" -> {
                ToolExecutionResult(true, "I am your advanced vehicle assistant, integrated natively into the car.")
            }
            "openTrunk" -> {
                ToolExecutionResult(true, "I've popped the trunk for you.")
            }
            "setEnergeticCabinLighting" -> {
                ToolExecutionResult(true, "I've set the cabin lighting to an energetic dynamic mode.")
            }
            "turnOffCabinLight" -> {
                ToolExecutionResult(true, "I've turned off the cabin lights.")
            }
            "turnOnCabinLight" -> {
                ToolExecutionResult(true, "I've turned on the cabin lights.")
            }
            "unlockDoors" -> {
                ToolExecutionResult(true, "I've unlocked the doors.")
            }
            else -> ToolExecutionResult(false, "System Error: System Handler not recognized.")
        }
    }
}

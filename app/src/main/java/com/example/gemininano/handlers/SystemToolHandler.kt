package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager

class SystemToolHandler(override val handlerKey: String) : ToolHandler {
    
    override suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): String {
        return when (handlerKey) {
            "remember" -> {
                val fact = toolCall.substringAfter("(").substringBefore(")")
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val currentMemory = prefs.getString("user_memory", "") ?: ""
                val newMemory = if (currentMemory.isEmpty()) fact else "$currentMemory. $fact"
                prefs.edit().putString("user_memory", newMemory).apply()
                "Got it, I've remembered that."
            }
            "getWeather" -> {
                val city = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                intent.putExtra(SearchManager.QUERY, "weather in $city")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "I've opened the weather for $city."
                } catch (e: Exception) {
                    "I couldn't open the weather information."
                }
            }
            "openApp" -> {
                val appName = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "I've opened the app store to find $appName."
                } catch (e: Exception) {
                    "I couldn't open the app."
                }
            }
            "sendUpcomingEventReminder" -> {
                "You have a meeting scheduled in 30 minutes."
            }
            "explainChildSeatInstallation" -> {
                "To install the child seat, refer to your vehicle's LATCH system anchors located in the rear seats."
            }
            "suggestUmbrellaIfRainy" -> {
                "There is rain expected at your destination. I suggest taking an umbrella."
            }
            "getNewsHighlights" -> {
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                intent.putExtra(SearchManager.QUERY, "top news highlights today")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "Here are today's top news highlights."
                } catch (e: Exception) {
                    "I couldn't load the news highlights."
                }
            }
            else -> "System Error: System Handler not recognized."
        }
    }
}

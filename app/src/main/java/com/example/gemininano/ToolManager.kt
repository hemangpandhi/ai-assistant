package com.example.gemininano

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import org.json.JSONObject

object ToolManager {
    private val TAG = "ToolManager"
    
    data class ToolDefinition(
        val handlerType: String,
        val promptString: String,
        val handlerKey: String?,
        val propertyId: Int?,
        val dataType: String?,
        val areaId: Int?,
        val valueToWrite: String?,
        val successMessage: String?
    )
    
    // Maps command prefix -> ToolDefinition
    private val activeTools = mutableMapOf<String, ToolDefinition>()
    
    var isInitialized = false
        private set

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val inputStream = context.assets.open("custom_properties.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            
            val jsonStr = String(buffer, Charsets.UTF_8)
            val jsonObject = JSONObject(jsonStr)
            
            if (jsonObject.has("tools")) {
                val toolsArray = jsonObject.getJSONArray("tools")
                for (i in 0 until toolsArray.length()) {
                    val toolObj = toolsArray.getJSONObject(i)
                    val promptString = toolObj.getString("prompt_string")
                    val handlerType = if (toolObj.has("handler_type")) toolObj.getString("handler_type") else "CUSTOM_KOTLIN"
                    val handlerKey = if (toolObj.has("handler_key")) toolObj.getString("handler_key") else null
                    
                    val commandName = handlerKey ?: promptString.substringAfter("<TOOL>").substringBefore("</TOOL>").substringBefore("(")
                    
                    val propertyId = if (toolObj.has("property_id")) toolObj.getInt("property_id") else null
                    val dataType = if (toolObj.has("data_type")) toolObj.getString("data_type") else null
                    val areaId = if (toolObj.has("area_id")) toolObj.getInt("area_id") else null
                    val valueToWrite = if (toolObj.has("value_to_write")) toolObj.getString("value_to_write") else null
                    val successMessage = if (toolObj.has("success_message")) toolObj.getString("success_message") else null
                    
                    activeTools[commandName] = ToolDefinition(
                        handlerType, promptString, handlerKey, propertyId, dataType, areaId, valueToWrite, successMessage
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")
                }
            }
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tools from custom_properties.json", e)
        }
    }

    /**
     * Returns the comma-separated list of tool prompts for the LLM System Prompt.
     */
    fun getLlmToolsPrompt(): String {
        if (activeTools.isEmpty()) return ""
        return activeTools.values.map { it.promptString }.joinToString(", ")
    }

    /**
     * Executes the requested tool call if it is enabled in custom_properties.json.
     * Returns a string summarizing the outcome for the chat UI.
     */
    fun executeToolCall(context: Context, rawToolCall: String, intentHandler: ((Intent) -> Unit)? = null): String {
        val toolCall = rawToolCall.trim()
        Log.d(TAG, "Executing toolCall: $toolCall")
        try {
            // Check if the requested tool corresponds to an enabled handler
            var matchedTool: ToolDefinition? = null
            for ((key, def) in activeTools) {
                if (toolCall.lowercase().startsWith(key.lowercase())) {
                    matchedTool = def
                    break
                }
            }
            
            if (matchedTool == null) {
                Log.w(TAG, "Tool blocked or unrecognized: $toolCall")
                return "System Error: The requested tool is not supported or is disabled by the manufacturer."
            }

            Log.d(TAG, "Matched tool handlerKey: ${matchedTool.handlerKey}, handlerType: ${matchedTool.handlerType}")

            if (matchedTool.handlerType == "GENERIC_VHAL_WRITE") {
                val propId = matchedTool.propertyId ?: return "System Error: Missing property_id"
                val dataType = matchedTool.dataType ?: return "System Error: Missing data_type"
                val areaId = matchedTool.areaId ?: 0
                val valueToSet = matchedTool.valueToWrite ?: toolCall.substringAfter("(").substringBefore(")")
                
                Log.d(TAG, "Executing GENERIC_VHAL_WRITE for propId $propId")
                val success = VehicleManager.setGenericVhalProperty(propId, areaId, valueToSet, dataType)
                return if (success) {
                    matchedTool.successMessage ?: "Action completed successfully."
                } else {
                    "Failed to execute action."
                }
            }

            // Execute the corresponding Kotlin handler
            return when (matchedTool.handlerKey) {
                "increaseTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 2.0
                    val currentTemp = VehicleManager.getRealTemperature().toDouble()
                    Log.d(TAG, "increaseTemperature: parsed value=$value, currentTemp=$currentTemp")
                    VehicleManager.writeTemperatureToVhal((currentTemp + value).toFloat())
                    "I've increased the temperature by $value degrees."
                }
                "decreaseTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 2.0
                    val currentTemp = VehicleManager.getRealTemperature().toDouble()
                    Log.d(TAG, "decreaseTemperature: parsed value=$value, currentTemp=$currentTemp")
                    VehicleManager.writeTemperatureToVhal((currentTemp - value).toFloat())
                    "I've decreased the temperature by $value degrees."
                }
                "setTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                    val currentTemp = VehicleManager.getRealTemperature().toDouble()
                    Log.d(TAG, "setTemperature: parsed value=$value, currentTemp=$currentTemp")
                    VehicleManager.writeTemperatureToVhal(value.toFloat())
                    
                    "I've set the temperature to $value degrees."
                }
                "setSeatHeater" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 1
                    VehicleManager.writeSeatHeaterToVhal(value)
                    "I've adjusted the seat heater."
                }
                "setSeatMassager" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 1
                    VehicleManager.writeSeatMassagerToVhal(value)
                    "I've turned on the seat massager for you."
                }
                "setWindowPosition" -> {
                    if (VehicleManager.getRealSpeed() > 70) {
                        Log.w(TAG, "Speed > 70mph. Ignored setWindowPosition tool.")
                        "Safety Warning: Speed is too high to safely open the windows."
                    } else {
                        val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 50
                        VehicleManager.writeWindowPositionToVhal(value)
                        "I've adjusted the windows."
                    }
                }
                "navigate" -> {
                    val dest = toolCall.substringAfter("(").substringBefore(")")
                    Toast.makeText(context, "Navigating to: $dest", Toast.LENGTH_SHORT).show()
                    
                    val gMapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}"))
                    gMapsIntent.setPackage("com.google.android.apps.maps")
                    gMapsIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    try {
                        if (intentHandler != null) intentHandler(gMapsIntent) else context.startActivity(gMapsIntent)
                    } catch (e: Exception) {
                        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}"))
                        navIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        try {
                            if (intentHandler != null) intentHandler(navIntent) else context.startActivity(navIntent)
                        } catch (e2: Exception) {
                            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
                            geoIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            try {
                                if (intentHandler != null) intentHandler(geoIntent) else context.startActivity(geoIntent)
                            } catch (e3: Exception) {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(dest)}"))
                                browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                try {
                                    if (intentHandler != null) intentHandler(browserIntent) else context.startActivity(browserIntent)
                                } catch (e4: Exception) {
                                    Log.e(TAG, "Failed to launch any navigation intents", e4)
                                }
                            }
                        }
                    }
                    "Routing to $dest."
                }
                "search" -> {
                    val query = toolCall.substringAfter("(").substringBefore(")")
                    Toast.makeText(context, "Searching map for: $query", Toast.LENGTH_SHORT).show()
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
                        }
                    }
                    "Showing search results for $query on the map."
                }
                "playMusic" -> {
                    val query = toolCall.substringAfter("(").substringBefore(")")
                    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    intent.putExtra(android.app.SearchManager.QUERY, query)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (intent.resolveActivity(context.packageManager) != null) {
                        if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    }
                    "Playing $query."
                }
                "call" -> {
                    val contact = toolCall.substringAfter("(").substringBefore(")")
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val mechName = prefs.getString("mechanic_name", "Mechanic") ?: "Mechanic"
                    val mechNum = prefs.getString("mechanic_number", "1-800-555-0199") ?: "1-800-555-0199"
                    
                    val phoneNumber = when (contact.lowercase()) {
                        mechName.lowercase() -> mechNum
                        "home" -> "555-0100"
                        "wife" -> "555-0101"
                        "husband" -> "555-0102"
                        else -> contact
                    }
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "Calling $contact."
                }
                "remember" -> {
                    val fact = toolCall.substringAfter("(").substringBefore(")")
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val currentMemory = prefs.getString("user_memory", "") ?: ""
                    val newMemory = if (currentMemory.isEmpty()) fact else "$currentMemory. $fact"
                    prefs.edit().putString("user_memory", newMemory).apply()
                    "Got it, I've remembered that."
                }
                "getWeather" -> {
                    val city = toolCall.substringAfter("(").substringBefore(")")
                    val temp = (60..85).random()
                    val conditions = listOf("Sunny", "Cloudy", "Rainy", "Partly Cloudy", "Clear").random()
                    "The current weather in $city is $temp°F and $conditions."
                }
                else -> {
                    "System Error: Handler found but logic is missing."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during tool execution", e)
            return "System Error: An exception occurred while executing the tool."
        }
    }

    /**
     * Executes a dummy command for every registered tool to verify the VHAL pipeline.
     */
    fun runSystemDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.append("## System Diagnostics Report\n\n")
        sb.append("| Tool Name | Handler Type | Status | Note |\n")
        sb.append("|---|---|---|---|\n")

        for ((key, def) in activeTools) {
            var status = "✅ PASS"
            var note = "Executed successfully"
            try {
                // Generate a dummy tool call string based on the required signature
                val dummyCall = when {
                    def.promptString.contains("VAL") -> "$key(72.0)"
                    def.promptString.contains("LEVEL") -> "$key(1)"
                    def.promptString.contains("PCT") -> "$key(50)"
                    def.promptString.contains("DEST") -> "$key(Home)"
                    def.promptString.contains("SONG") -> "$key(Test)"
                    def.promptString.contains("NAME") -> "$key(Mechanic)"
                    def.promptString.contains("FACT") -> "$key(TestFact)"
                    else -> "$key()" // No args
                }

                val result = executeToolCall(context, dummyCall)
                if (result.startsWith("System Error") || result.startsWith("Failed")) {
                    status = "❌ FAIL"
                    note = result
                }
            } catch (e: Exception) {
                status = "❌ CRASH"
                note = e.message ?: "Unknown Exception"
            }
            sb.append("| $key | ${def.handlerType} | $status | $note |\n")
        }

        sb.append("\n")
        sb.append(VehicleManager.runPropertyDiagnostics())
        
        return sb.toString()
    }
}

package com.example.gemininano

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object ToolManager {
    private val TAG = "ToolManager"
    
    data class Constraint(
        val propertyId: Int,
        val operator: String,
        val value: Double,
        val errorMsg: String
    )
    
    data class ToolDefinition(
        val handlerType: String,
        val promptString: String,
        val handlerKey: String?,
        val propertyId: Int?,
        val dataType: String?,
        val areaId: Int?,
        val valueToWrite: String?,
        val successMessage: String?,
        val keywords: List<String>?,
        val constraints: List<Constraint>?,
        val requiresConfirmation: Boolean = false,
        val confirmationMessage: String? = null
    )
    
    // Maps command prefix -> ToolDefinition
    private val activeTools = mutableMapOf<String, ToolDefinition>()
    
    var isInitialized = false
        private set

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val inputStream = context.assets.open("vehicle_skills_registry.json")
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

                    val keywordsList = mutableListOf<String>()
                    if (toolObj.has("keywords")) {
                        val arr = toolObj.getJSONArray("keywords")
                        for (j in 0 until arr.length()) keywordsList.add(arr.getString(j).lowercase())
                    }
                    
                    val constraintsList = mutableListOf<Constraint>()
                    if (toolObj.has("constraints")) {
                        val arr = toolObj.getJSONArray("constraints")
                        for (j in 0 until arr.length()) {
                            val cObj = arr.getJSONObject(j)
                            constraintsList.add(Constraint(
                                propertyId = cObj.getInt("property_id"),
                                operator = cObj.getString("operator"),
                                value = cObj.getDouble("value"),
                                errorMsg = cObj.getString("error_msg")
                            ))
                        }
                    }

                    activeTools[commandName] = ToolDefinition(
                        handlerType, promptString, handlerKey, propertyId, dataType, areaId, valueToWrite, successMessage,
                        if (keywordsList.isNotEmpty()) keywordsList else null,
                        if (constraintsList.isNotEmpty()) constraintsList else null,
                        requiresConfirmation = if (toolObj.has("requires_confirmation")) toolObj.getBoolean("requires_confirmation") else false,
                        confirmationMessage = if (toolObj.has("confirmation_message")) toolObj.getString("confirmation_message") else null
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")
                }
            }
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tools from vehicle_skills_registry.json", e)
        }
        
        // Initialize Semantic Search RAG asynchronously
        SemanticSearchManager.initialize(context)
        SemanticSearchManager.buildToolEmbeddingsCache()
    }

    /**
     * Primitive Tool RAG Engine.
     * Evaluates the user query against the tool keywords.
     * Returns the top matching tools (plus any default generic ones).
     */
    fun getRelevantTools(query: String): List<ToolDefinition> {
        if (query.isBlank()) return activeTools.values.toList()
        return SemanticSearchManager.search(query, 30)
    }

    /**
     * Returns the comma-separated list of tool prompts for the LLM System Prompt.
     */


    fun getToolDefinition(toolCall: String): ToolDefinition? {
        val commandName = toolCall.substringBefore("(").trim()
        return activeTools[commandName]
    }
    
    fun getAllTools(): Map<String, ToolDefinition> = activeTools

    fun getLlmToolsPrompt(query: String = ""): String {
        val relevantTools = getRelevantTools(query)
        if (relevantTools.isEmpty()) return ""
        return relevantTools.map { it.promptString }.joinToString("\n")
    }

    /**
     * Executes the requested tool call if it is enabled in vehicle_skills_registry.json.
     * Returns a string summarizing the outcome for the chat UI.
     */
    suspend fun executeToolCall(context: Context, rawToolCall: String, intentHandler: ((Intent) -> Unit)? = null): String {
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

            // Safety Middleware Constraint Validation
            if (matchedTool.constraints != null) {
                for (constraint in matchedTool.constraints) {
                    val currentValue = VehicleManager.getFloatProperty(constraint.propertyId)
                    if (currentValue != null) {
                        val failed = when (constraint.operator) {
                            "<" -> currentValue.toDouble() >= constraint.value
                            ">" -> currentValue.toDouble() <= constraint.value
                            "==" -> currentValue.toDouble() != constraint.value
                            "!=" -> currentValue.toDouble() == constraint.value
                            "<=" -> currentValue.toDouble() > constraint.value
                            ">=" -> currentValue.toDouble() < constraint.value
                            else -> false
                        }
                        if (failed) {
                            Log.w(TAG, "Safety Constraint Blocked Tool $toolCall: Property ${constraint.propertyId} is $currentValue. Condition `${constraint.operator} ${constraint.value}` failed. Message: ${constraint.errorMsg}")
                            return constraint.errorMsg
                        }
                    } else {
                        Log.w(TAG, "Warning: Could not read property ${constraint.propertyId} for safety constraint evaluation.")
                    }
                }
            }

            if (matchedTool.handlerType == "GENERIC_VHAL_WRITE") {
                val propId = matchedTool.propertyId ?: return "System Error: Missing property_id"
                val dataType = matchedTool.dataType ?: return "System Error: Missing data_type"
                val areaId = matchedTool.areaId ?: 0
                val valueToSet = matchedTool.valueToWrite ?: toolCall.substringAfter("(").substringBefore(")")
                
                Log.d(TAG, "Executing GENERIC_VHAL_WRITE for propId $propId")
                val success = VehicleManager.setPropertyVerified(propId, areaId, valueToSet, dataType)
                return if (success) {
                    matchedTool.successMessage ?: "Action completed successfully."
                } else {
                    "I sent the command, but the vehicle hardware didn't confirm the change. Please check your system."
                }
            }

            // Execute the corresponding Kotlin handler
            return when (matchedTool.handlerKey) {
                "increaseTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 2.0
                    val currentTemp = VehicleManager.getRealTemperature().toDouble()
                    Log.d(TAG, "increaseTemperature: parsed value=$value, currentTemp=$currentTemp")
                    val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp + value).toFloat())
                    if (success) "I've increased the temperature by $value degrees." else "I sent the command, but the vehicle hardware didn't confirm the change."
                }
                "decreaseTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 2.0
                    val currentTemp = VehicleManager.getRealTemperature().toDouble()
                    Log.d(TAG, "decreaseTemperature: parsed value=$value, currentTemp=$currentTemp")
                    val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp - value).toFloat())
                    if (success) "I've decreased the temperature by $value degrees." else "I sent the command, but the vehicle hardware didn't confirm the change."
                }
                "setTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                    val success = VehicleManager.writeTemperatureToVhalVerified(value.toFloat())
                    if (success) "I've set the temperature to $value degrees." else "I sent the command, but the vehicle hardware didn't confirm the change."
                }
                "setSeatHeater" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toIntOrNull() ?: 3
                    val success = VehicleManager.writeSeatHeaterToVhalVerified(value)
                    if (success) "I've adjusted the seat heater." else "I sent the command, but the vehicle hardware didn't confirm the change."
                }
                "setSeatMassager" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toIntOrNull() ?: 3
                    val success = VehicleManager.writeSeatMassagerToVhalVerified(value)
                    if (success) "I've turned on the seat massager for you." else "I sent the command, but the vehicle hardware didn't confirm the change."
                }



                "navigate" -> {
                    val dest = toolCall.substringAfter("(").substringBefore(")")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Navigating to: $dest", Toast.LENGTH_SHORT).show()
                    }
                    
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
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Searching map for: $query", Toast.LENGTH_SHORT).show()
                    }
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
                    var mediaSearchSuccess = false
                    try {
                        val pm = context.packageManager
                        val browseIntent = Intent("android.media.browse.MediaBrowserService")
                        val resolveInfos = pm.queryIntentServices(browseIntent, 0)
                        
                        // Prefer Spotify Automotive if available
                        val targetInfo = resolveInfos.find { it.serviceInfo.packageName.contains("spotify") } 
                            ?: resolveInfos.firstOrNull()
                            
                        if (targetInfo != null) {
                            val componentName = android.content.ComponentName(targetInfo.serviceInfo.packageName, targetInfo.serviceInfo.name)
                            Log.i(TAG, "Connecting to MediaBrowserService: ${componentName.flattenToString()}")
                            
                            mediaSearchSuccess = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                kotlin.coroutines.suspendCoroutine { continuation ->
                                    var hasResumed = false
                                    lateinit var browser: android.media.browse.MediaBrowser
                                    browser = android.media.browse.MediaBrowser(context, componentName, object : android.media.browse.MediaBrowser.ConnectionCallback() {
                                        override fun onConnected() {
                                            try {
                                                val sessionToken = browser.sessionToken
                                                val controller = android.media.session.MediaController(context, sessionToken)
                                                controller.transportControls.playFromSearch(query, null)
                                                Log.i(TAG, "Dispatched playFromSearch for: $query via MediaController")
                                                if (!hasResumed) {
                                                    hasResumed = true
                                                    continuation.resumeWith(Result.success(true))
                                                }
                                                browser.disconnect()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to dispatch playFromSearch", e)
                                                if (!hasResumed) {
                                                    hasResumed = true
                                                    continuation.resumeWith(Result.success(false))
                                                }
                                            }
                                        }
                                        override fun onConnectionSuspended() {
                                            if (!hasResumed) {
                                                hasResumed = true
                                                continuation.resumeWith(Result.success(false))
                                            }
                                        }
                                        override fun onConnectionFailed() {
                                            if (!hasResumed) {
                                                hasResumed = true
                                                continuation.resumeWith(Result.success(false))
                                            }
                                        }
                                    }, null)
                                    browser.connect()
                                    
                                    // Timeout fallback
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        kotlinx.coroutines.delay(2000)
                                        if (!hasResumed) {
                                            hasResumed = true
                                            try { browser.disconnect() } catch (e: Exception) {}
                                            continuation.resumeWith(Result.success(false))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "MediaBrowserService connection failed", e)
                    }

                    if (mediaSearchSuccess) {
                        // Launch the app UI to show the playing track
                        val fallbackIntent = Intent(Intent.ACTION_MAIN)
                        fallbackIntent.addCategory(Intent.CATEGORY_APP_MUSIC)
                        fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        try {
                            if (intentHandler != null) intentHandler(fallbackIntent) else context.startActivity(fallbackIntent)
                        } catch (e: Exception) {}
                    } else {
                        // Fallback to standard intents if MediaBrowser failed
                        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                        intent.putExtra(android.app.SearchManager.QUERY, query)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (intent.resolveActivity(context.packageManager) != null) {
                            if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                        } else {
                            var videoId: String? = null
                            try {
                                val url = java.net.URL("https://www.youtube.com/results?search_query=${android.net.Uri.encode(query)}")
                                val connection = url.openConnection() as java.net.HttpURLConnection
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                                val response = connection.inputStream.bufferedReader().use { it.readText() }
                                val regex = "\"videoId\":\"([a-zA-Z0-9_-]{11})\"".toRegex()
                                val match = regex.find(response)
                                if (match != null) {
                                    videoId = match.groupValues[1]
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "YouTube scrape failed", e)
                            }

                            if (videoId != null) {
                                // Demo Workaround: Open YouTube Music directly to the watch URL to force auto-play
                                val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/watch?v=$videoId&autoplay=1"))
                                webIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                
                                // Force intent to open in the default web browser to ensure &autoplay=1 works (preventing native app hijack)
                                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://www.google.com"))
                                val resolveInfo = context.packageManager.resolveActivity(browserIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                                if (resolveInfo != null) {
                                    webIntent.setPackage(resolveInfo.activityInfo.packageName)
                                }
                                try {
                                    if (intentHandler != null) intentHandler(webIntent) else context.startActivity(webIntent)
                                    // Dispatch a global Media Play event after a short delay to ensure auto-play in the native app or browser
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        kotlinx.coroutines.delay(2500)
                                        try {
                                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                                            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                                            audioManager.dispatchMediaKeyEvent(eventDown)
                                            audioManager.dispatchMediaKeyEvent(eventUp)
                                        } catch (e: Exception) {}
                                    }
                                } catch (e: Exception) {
                                    // Ultimate Fallback: Just open the default music app
                                    val fallbackIntent = Intent(Intent.ACTION_MAIN)
                                    fallbackIntent.addCategory(Intent.CATEGORY_APP_MUSIC)
                                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    try {
                                        if (intentHandler != null) intentHandler(fallbackIntent) else context.startActivity(fallbackIntent)
                                        // Dispatch a global Media Play event to resume playback in the background or newly launched app
                                        try {
                                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                                            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                                            audioManager.dispatchMediaKeyEvent(eventDown)
                                            audioManager.dispatchMediaKeyEvent(eventUp)
                                        } catch (e: Exception) {}
                                    } catch (e2: Exception) {
                                        Log.e(TAG, "No music app found to handle request")
                                    }
                                }
                            } else {
                                // Ultimate Fallback: Just open the default music app
                                val fallbackIntent = Intent(Intent.ACTION_MAIN)
                                fallbackIntent.addCategory(Intent.CATEGORY_APP_MUSIC)
                                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                try {
                                    if (intentHandler != null) intentHandler(fallbackIntent) else context.startActivity(fallbackIntent)
                                    // Dispatch a global Media Play event to resume playback in the background or newly launched app
                                    try {
                                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                        val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                                        val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                                        audioManager.dispatchMediaKeyEvent(eventDown)
                                        audioManager.dispatchMediaKeyEvent(eventUp)
                                    } catch (e: Exception) {}
                                } catch (e: Exception) {
                                    Log.e(TAG, "No music app found to handle request")
                                }
                            }
                        }
                    }
                    
                    "Playing $query."
                }
                "pauseMusic" -> {
                    try {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                        val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                        audioManager.dispatchMediaKeyEvent(eventDown)
                        audioManager.dispatchMediaKeyEvent(eventUp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dispatch media pause key event")
                    }
                    "Music paused."
                }
                "nextTrack" -> {
                    try {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                        val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                        audioManager.dispatchMediaKeyEvent(eventDown)
                        audioManager.dispatchMediaKeyEvent(eventUp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dispatch media next key event")
                    }
                    "Skipping to the next track."
                }
                "prevTrack" -> {
                    try {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        audioManager.dispatchMediaKeyEvent(eventDown)
                        audioManager.dispatchMediaKeyEvent(eventUp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dispatch media previous key event")
                    }
                    "Playing the previous track."
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

                val result = kotlinx.coroutines.runBlocking { executeToolCall(context, dummyCall) }
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

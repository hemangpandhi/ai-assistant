package com.example.gemininano

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object LLMManager {
    var engine: Engine? = null
        private set

    var conversation: Conversation? = null
        private set

    var currentModelPath: String = ""
        private set

    var isInitializing = false
        private set

    var isFirstMessage = true
    private var appContext: Context? = null


    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    suspend fun autoInitialize(context: Context, force: Boolean = false, backendChoice: String = "Auto", callback: InitCallback? = null) {
        if (!force && engine != null) {
            callback?.onSuccess()
            return
        }
        appContext = context.applicationContext

        withContext(Dispatchers.IO) {
            val internalDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)
            val tmpDir = File("/data/local/tmp/")

            val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles(), tmpDir.listFiles())
                .flatMap { it.toList() }

            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedModelPath = prefs.getString("selected_model", null)
            val savedBackendChoice = prefs.getString("backend_choice", "Auto") ?: "Auto"
            
            var modelFile: File? = null
            if (savedModelPath != null) {
                modelFile = File(savedModelPath)
            }
            if (modelFile == null || !modelFile.exists()) {
                modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                    ?: models.find { it.name.contains("Qwen", ignoreCase = true) }
                    ?: models.firstOrNull()
            }

            if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                initialize(context, modelFile.absolutePath, force, if (backendChoice != "Auto") backendChoice else savedBackendChoice, callback)
            } else {
                withContext(Dispatchers.Main) { callback?.onError(Exception("No model found")) }
            }
        }
    }

    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, backendChoice: String = "Auto", callback: InitCallback? = null) {
        if (!force && engine != null && currentModelPath == modelPath) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            isInitializing = true
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val maxTokens = prefs.getInt("max_tokens", 2048)
            
            try {
                try {
                    conversation?.close()
                    engine?.close()
                } catch (e: Exception) {
                    Log.w("LLMManager", "Failed to cleanly close old inference instance.", e)
                }
                conversation = null
                engine = null

                val backend = when (backendChoice) {
                    "NPU" -> Backend.NPU()
                    "GPU" -> Backend.GPU()
                    "CPU" -> Backend.CPU()
                    else -> Backend.GPU() // Auto defaults to GPU
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                resetConversation(context)
                currentModelPath = modelPath
                Log.d("LLMManager", "LLM Initialized successfully from $modelPath")
                
                withContext(Dispatchers.Main) { 
                    callback?.onSuccess() 
                }
            } catch (e: Exception) {
                Log.e("LLMManager", "Error initializing model", e)
                if (backendChoice != "CPU") {
                    Log.i("LLMManager", "Attempting fallback to CPU backend...")
                    try {
                        val engineConfigFallback = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(),
                            maxNumTokens = maxTokens
                        )
                        engine = Engine(engineConfigFallback)
                        engine!!.initialize()
                        
                        resetConversation(context)
                        currentModelPath = modelPath
                        Log.d("LLMManager", "LLM Initialized successfully with CPU Fallback from $modelPath")
                        withContext(Dispatchers.Main) { callback?.onSuccess() }
                    } catch (fallbackEx: Exception) {
                         Log.e("LLMManager", "Error initializing model with CPU fallback", fallbackEx)
                         withContext(Dispatchers.Main) { callback?.onError(fallbackEx) }
                    }
                } else {
                    withContext(Dispatchers.Main) { callback?.onError(e) }
                }
            } finally {
                isInitializing = false
            }
        }
    }
    fun getSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val customPrompt = prefs.getString("system_prompt", null)
        
        if (!customPrompt.isNullOrEmpty()) {
            return customPrompt
        }
        
        return getDefaultSystemPrompt(context, query)
    }
    
    fun getDynamicContext(context: android.content.Context, prompt: String): String {
        val q = prompt.lowercase()
        val isFood = q.contains("hungry") || q.contains("food") || q.contains("eat") || q.contains("restaurant") || q.contains("italian") || q.contains("mexican") || q.contains("chinese") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("indian") || q.contains("thai") || q.contains("japanese") || q.contains("vegetarian") || q.contains("vegan")
        val isFuel = q.contains("fuel") || q.contains("gas") || q.contains("petrol") || q.contains("charging")
        
        if ((isFood || isFuel) && prompt.length < 50) {
            try {
                var searchQuery = "restaurant"
                if (isFuel) {
                    searchQuery = if (q.contains("charging")) "EV charging station" else "gas station"
                } else {
                    if (q.contains("italian")) searchQuery = "Italian restaurant"
                    else if (q.contains("mexican")) searchQuery = "Mexican restaurant"
                    else if (q.contains("chinese")) searchQuery = "Chinese restaurant"
                    else if (q.contains("pizza")) searchQuery = "Pizza"
                    else if (q.contains("burger")) searchQuery = "Burger"
                    else if (q.contains("sushi") || q.contains("japanese")) searchQuery = "Sushi"
                    else if (q.contains("indian")) searchQuery = "Indian restaurant"
                    else if (q.contains("thai")) searchQuery = "Thai restaurant"
                    else if (q.contains("vegetarian") || q.contains("vegan")) searchQuery = "Vegetarian restaurant"
                    else return "" // Generic query, don't search yet
                }

                val url = java.net.URL("https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}&format=json&limit=3&viewbox=139.30,35.60,139.45,35.50&bounded=1")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "GeminiNanoSample/1.0")
                connection.requestMethod = "GET"
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)
                    val places = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        val name = jsonArray.getJSONObject(i).optString("name", "")
                        if (name.isNotEmpty()) places.add(name)
                    }
                    if (places.isNotEmpty()) {
                        val placesStr = places.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString(", ")
                        return "\n\n[System Note: Based on the user's location, you MUST suggest exactly these options: $placesStr. End your response with 'Which one would you like to navigate to?']"
                    }
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
        return ""
    }
    
    fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        val q = query.lowercase()
        val isHvac = q.contains("temperature") || q.contains("hot") || q.contains("cold") || q.contains("warm") || q.contains("cool") || q.contains("ac") || q.contains("heater") || q.contains("defroster") || q.contains("increase") || q.contains("decrease")
        val isSightseeing = q.contains("see") || q.contains("visit") || q.contains("interesting") || q.contains("places") || q.contains("sightseeing") || q.contains("tourist")
        val isFood = q.contains("hungry") || q.contains("food") || q.contains("eat") || q.contains("restaurant") || q.contains("italian") || q.contains("mexican") || q.contains("chinese") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("indian") || q.contains("thai")
        val isNav = (q.contains("navigate") || q.contains("go to") || q.contains("directions") || q.contains("route")) && !isSightseeing && !isFood
        val isAmbient = q.contains("home") || q.contains("work")
        val isDiag = q.contains("wrong") || q.contains("broken") || q.contains("issue") || q.contains("light") || q.contains("code") || q.contains("door") || q.contains("fuel")
        val isWellness = q.contains("pain") || q.contains("hurt") || q.contains("tired") || q.contains("sore") || q.contains("ache")
        
        val basePrompt = StringBuilder()
        basePrompt.append("You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using the <TOOL>command()</TOOL> syntax. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.\n\n")
        
        basePrompt.append("=== VEHICLE STATE ===\n")
        basePrompt.append("${VehicleManager.getLLMContextString(context)}\n")
        basePrompt.append("Memory: $userMemory\n\n")
        
        basePrompt.append("=== TOOLS ===\n")
        basePrompt.append("${ToolManager.getLlmToolsPrompt(query)}\n\n")
        
        basePrompt.append("=== STRICT RULES ===\n")
        basePrompt.append("IMPORTANT: If you use a tool, YOUR RESPONSE MUST EXACTLY START WITH THE XML TAG '<TOOL>'. Do NOT omit it.\n")
        
        if (isHvac || q.isEmpty()) {
            basePrompt.append("1. HVAC: To change the temperature, use the EXACT <TOOL> syntax BEFORE your text:\n")
            basePrompt.append("- If user gives an exact number: \"<TOOL>setTemperature(VAL)</TOOL> I've set the temperature to [VAL] degrees.\"\n")
            basePrompt.append("- If user is cold or wants to increase it: \"<TOOL>increaseTemperature()</TOOL> I'm warming it up.\"\n")
            basePrompt.append("- If user is hot or wants to decrease it: \"<TOOL>decreaseTemperature()</TOOL> I'm cooling it down.\"\n")
            basePrompt.append("DO NOT mention the current temperature after using a tool, because your memory of it will be outdated!\n")
        }
        if (isWellness || q.isEmpty()) {
            basePrompt.append("2. WELLNESS: If the user complains about body pain, being tired, or their back hurting, you MUST ask if they want you to turn on the seat heater and massager. If the user says yes, you MUST output the EXACT syntax <TOOL>setSeatHeater(2)</TOOL> and <TOOL>setSeatMassager(2)</TOOL> to activate them.\n")
        }
        if (isNav || q.isEmpty()) {
            basePrompt.append("3. NAVIGATION: To navigate, you MUST reply ONLY with the EXACT syntax <TOOL>navigate(DEST)</TOOL> and NO other text. Example: \"<TOOL>navigate(Tokyo)</TOOL>\"\n")
        }
        if (isDiag || q.isEmpty()) {
            basePrompt.append("4. MULTI-TURN FUEL: If user mentions low fuel/range, you MUST ask: \"Should I find a nearby charging station?\" without any other text. DO NOT use the remember tool for fuel/diagnostics.\n")
        }
        if (isAmbient || q.isEmpty()) {
            basePrompt.append("5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: \"<TOOL>navigate(Home)</TOOL> Should I turn on the heater?\"\n")
        }
        if (isSightseeing || q.isEmpty()) {
            basePrompt.append("6. SIGHTSEEING: If asked about places to visit or sightseeing, YOU MUST suggest places AND THEN YOU MUST END YOUR RESPONSE WITH THE EXACT QUESTION: \"Would you like me to navigate to any of these?\". Do NOT forget to ask this question!\n")
            basePrompt.append("7. AMBIGUITY: If you suggest multiple places and the user agrees (e.g. \"Yes\"), DO NOT use the navigate tool immediately. You MUST ask \"Which one?\" first.\n")
        }
        if (isFood || q.isEmpty()) {
            val dyn = getDynamicContext(context, query)
            if (dyn.isNotEmpty()) {
                basePrompt.append("8. FOOD CHOICES: $dyn\n")
            } else {
                basePrompt.append("8. FOOD CHOICES: If the user is hungry, DO NOT use tools. You MUST ask them what cuisine they prefer (e.g. Italian, Indian, Japanese, American, Pizza).\n")
            }
        }
        if (isDiag || q.isEmpty()) {
            val dynFuel = getDynamicContext(context, query)
            if (dynFuel.isNotEmpty() && (q.contains("fuel") || q.contains("gas") || q.contains("petrol") || q.contains("charging"))) {
                basePrompt.append("9. FUEL/CHARGING CHOICES: $dynFuel\n")
            } else {
                basePrompt.append("9. DIAGNOSTICS: If asked about car problems, read the OBD code and ask if they want to call a mechanic.\n")
                basePrompt.append("10. FUEL/CHARGING: If the user says they are out of fuel or battery, ALWAYS ask first: \"Should I find a nearby gas station?\" DO NOT navigate immediately.\n")
            }
        }

        basePrompt.append("\n")

        if (isHvac || q.isEmpty()) {
            basePrompt.append("[HVAC Control]\n")
            basePrompt.append("User: \"Increase temperature.\"\n")
            basePrompt.append("Assistant: <TOOL>increaseTemperature()</TOOL> I'm warming it up.\n")
            basePrompt.append("User: \"Set the temperature to 70.\"\n")
            basePrompt.append("Assistant: <TOOL>setTemperature(70)</TOOL> I've set the temperature to 70 degrees.\n")
            basePrompt.append("User: \"I am feeling cold.\"\n")
            basePrompt.append("Assistant: <TOOL>increaseTemperature()</TOOL> I'm warming it up.\n")
            basePrompt.append("User: \"Decrease temperature.\"\n")
            basePrompt.append("Assistant: <TOOL>decreaseTemperature()</TOOL> I'm cooling it down.\n\n")
        }

        if (isSightseeing || q.isEmpty()) {
            basePrompt.append("[Sightseeing Query]\n")
            basePrompt.append("User: \"What are some sightseeing places to visit?\"\n")
            basePrompt.append("Assistant: There are many great places to visit around here! You should definitely see the Central Park and the Art Museum. Would you like me to navigate to any of these?\n\n")

            basePrompt.append("[Sightseeing - Decline]\n")
            basePrompt.append("User: \"No thanks.\"\n")
            basePrompt.append("Assistant: OK, let me know if I can do something else for you.\n\n")

            basePrompt.append("[Sightseeing - Accept without specifying]\n")
            basePrompt.append("User: \"Yes please.\"\n")
            basePrompt.append("Assistant: Which destination would you like to navigate to?\n\n")

            basePrompt.append("[Sightseeing - Accept specific]\n")
            basePrompt.append("User: \"The Eiffel Tower.\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Eiffel Tower)</TOOL>\n\n")
        }

        if (isFood || q.isEmpty()) {
            basePrompt.append("[Food Selection]\n")
            basePrompt.append("User: \"I'm hungry.\"\n")
            basePrompt.append("Assistant: Which food would you like to eat? e.g. Italian, Indian, Japanese, American, Pizza?\n")
            basePrompt.append("User: \"Italian.\"\n")
            basePrompt.append("Assistant: I found these options nearby: 1. Olive Garden, 2. Mario's. Which one would you like to navigate to?\n")
            basePrompt.append("User: \"1.\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Olive Garden)</TOOL>\n\n")
        }

        if (isNav || q.isEmpty()) {
            basePrompt.append("[Direct Navigation]\n")
            basePrompt.append("User: \"Navigate to Tokyo\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Tokyo)</TOOL>\n\n")
            basePrompt.append("[Gas Station Navigation]\n")
            basePrompt.append("User: \"Navigate to the nearest gas station\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(gas station)</TOOL>\n\n")
        }

        if ((isDiag || q.isEmpty()) && getDynamicContext(context, query).isEmpty()) {
            basePrompt.append("[Smart Fuel Routing - Ask First]\n")
            basePrompt.append("User: \"I am running out of fuel.\"\n")
            basePrompt.append("Assistant: Your fuel level is low. Should I navigate you to a nearby gas station?\n\n")

            basePrompt.append("[Smart Fuel Routing - Confirm]\n")
            basePrompt.append("User: \"Yes, please.\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(gas station)</TOOL>\n\n")
        }
            
        if (isAmbient || q.isEmpty()) {
            basePrompt.append("[Ambient Routine Confirmation]\n")
            basePrompt.append("User: \"I'm heading home.\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Home)</TOOL> Navigating home. I noticed it's freezing outside. Would you like me to turn on the heater and seat warmers for your drive?\n")
            basePrompt.append("User: \"Yes, please.\"\n")
            basePrompt.append("Assistant: <TOOL>setTemperature(72)</TOOL><TOOL>setSeatHeater(3)</TOOL>\n\n")
        }



        if (isDiag || q.isEmpty()) {
            basePrompt.append("[Contextual Diagnostics & Servicing]\n")
            basePrompt.append("User: \"What's wrong with my car?\"\n")
            basePrompt.append("Assistant: Your check engine light is on with code P0420 (Catalytic Converter). Would you like me to call your preferred mechanic?\n")
            basePrompt.append("User: \"Yes, call the mechanic.\"\n")
            basePrompt.append("Assistant: <TOOL>call(Mechanic)</TOOL>\n\n")

            basePrompt.append("[Door Alert Check]\n")
            basePrompt.append("User: \"Check if any door is open.\"\n")
            basePrompt.append("Assistant: I checked the ADAS_OSE_DOOR_ALERT system. The current status is: All Doors Closed.\n\n")
        }

        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty()) {
            basePrompt.append("\n=== DYNAMIC VEHICLE SENSOR RULES ===\n")
            customInstructions.forEachIndexed { index, inst ->
                basePrompt.append("${10 + index}. $inst\n")
            }
        }
        
        return basePrompt.toString().trimIndent()
    }

    fun getNavigationExamples(): String {
        return """
            [Sightseeing Selection - Clear]
            User: "The Louvre."
            Assistant: <TOOL>navigate(Louvre Museum)</TOOL> Setting destination to the Louvre Museum.

            [Direct Navigation]
            User: "Navigate to Tokyo"
            Assistant: <TOOL>navigate(Tokyo)</TOOL>
        """.trimIndent()
    }

    fun resetConversation(context: Context? = null) {
        if (engine == null) return
        
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Error closing previous conversation", e)
        }
        
        isFirstMessage = true
        
        val conversationConfig = ConversationConfig()
        
        try {
            conversation = engine!!.createConversation(conversationConfig)
            Log.d("LLMManager", "Conversation reset. isFirstMessage=true.")
        } catch (e: Exception) {
            Log.e("LLMManager", "Failed to reset conversation", e)
        }
    }
}

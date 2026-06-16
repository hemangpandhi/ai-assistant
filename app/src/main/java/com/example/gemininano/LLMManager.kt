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
import kotlinx.coroutines.sync.withLock
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
        
    var isPrewarming = false
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
    private val initMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, backendChoice: String = "Auto", callback: InitCallback? = null) {
        initMutex.withLock {
            if (!force && engine != null && currentModelPath == modelPath) {
                withContext(Dispatchers.Main) { callback?.onSuccess() }
                return
            }
    
            withContext(Dispatchers.IO) {
                isInitializing = true
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                // Removed Math.max(..., 4096) constraint so physical devices like Pixel Tablet can lower the KV Cache to prevent GPU OOM crashes
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
    }
    suspend fun getSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val customPrompt = prefs.getString("system_prompt", null)
        
        if (!customPrompt.isNullOrEmpty()) {
            return customPrompt
        }
        
        return getDefaultSystemPrompt(context, query)
    }
    
    suspend fun getDynamicContext(context: android.content.Context, prompt: String): String {
        val q = prompt.lowercase()
        val mem = MemoryManager.getSlidingWindowContext(200).lowercase()
        val isFoodQuery = q.contains("hungry") || q.contains("food") || q.contains("eat") || q.contains("restaurant") || q.contains("italian") || q.contains("mexican") || q.contains("chinese") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("indian") || q.contains("thai") || q.contains("japanese") || q.contains("vegetarian") || q.contains("vegan") || q.contains("american") || q.contains("fast food")
        val isFuelQuery = q.contains("fuel") || q.contains("gas") || q.contains("petrol") || q.contains("charging")
        
        val isFollowUpToSearch = (mem.contains("would you like to navigate") || mem.contains("found these options nearby") || mem.contains("navigate to any of these options")) && !isFoodQuery && !isFuelQuery
        val isFollowUpToFuel = (mem.contains("navigate you to a nearby gas station") || mem.contains("find a nearby gas station") || mem.contains("nearby charging station")) && !isFoodQuery
        
        val isNav = q.contains("navigate") || q.contains("go to") || q.contains("directions") || q.contains("route") || q.contains("take me") || (q.length <= 2 && q.toIntOrNull() != null) || isFollowUpToSearch
        val isFood = isFoodQuery && !isFollowUpToSearch
        val isFuel = (isFuelQuery || isFollowUpToFuel) && !isFollowUpToSearch
        val isSightseeing = (q.contains("visit") || q.contains("interesting") || q.contains("places") || q.contains("sightseeing") || q.contains("tourist") || q.contains("what to do") || q.contains("where to go") || q.contains("city") || q.contains("see")) && !isNav
        
        if ((isFood || isFuel) && prompt.length < 50) {
            try {
                var overpassQuery = "node[\"amenity\"=\"restaurant\"]"
                var isCuisineSpecific = false
                if (isFuel) {
                    if (!isFollowUpToFuel) {
                        return "\n\n[System Note: Do NOT use any <TOOL> tags. You MUST reply to the user with EXACTLY this text: \"Your fuel level is low. Should I find a nearby gas station?\"]"
                    }
                    overpassQuery = if (q.contains("charging") || mem.contains("charging")) "node[\"amenity\"=\"charging_station\"]" else "node[\"amenity\"=\"fuel\"]"
                } else {
                    if (q.contains("italian")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"italian\",i]"; isCuisineSpecific = true }
                    else if (q.contains("mexican")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"mexican\",i]"; isCuisineSpecific = true }
                    else if (q.contains("chinese")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"chinese\",i]"; isCuisineSpecific = true }
                    else if (q.contains("pizza")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"pizza\",i]"; isCuisineSpecific = true }
                    else if (q.contains("burger") || q.contains("fast food") || q.contains("american")) { overpassQuery = "node[\"amenity\"=\"fast_food\"][\"cuisine\"~\"burger|american\",i]"; isCuisineSpecific = true }
                    else if (q.contains("sushi") || q.contains("japanese")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"japanese|sushi\",i]"; isCuisineSpecific = true }
                    else if (q.contains("indian")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"indian\",i]"; isCuisineSpecific = true }
                    else if (q.contains("thai")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"cuisine\"~\"thai\",i]"; isCuisineSpecific = true }
                    else if (q.contains("vegetarian") || q.contains("vegan")) { overpassQuery = "node[\"amenity\"=\"restaurant\"][\"diet:vegan\"~\"yes\",i]"; isCuisineSpecific = true }
                    else {
                        return "\n\n[System Note: Do NOT use any <TOOL> tags. You MUST reply to the user with EXACTLY this text: \"Which food would you like to eat? e.g. Italian, Indian, Japanese, American, Pizza?\"]"
                    }
                }

                var bbox = "35.47,139.27,35.67,139.47" // south,west,north,east Default Sagamihara, Japan fallback
                try {
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    val locationOverride = prefs.getString("location_override", "139.37, 35.57") ?: "139.37, 35.57" // Default Sagamihara lon, lat
                    var lat = 0.0
                    var lon = 0.0
                    var useOverride = false
                    
                    if (locationOverride.isNotEmpty() && locationOverride.contains(",")) {
                        try {
                            val parts = locationOverride.split(",")
                            lon = parts[0].trim().toDouble()
                            lat = parts[1].trim().toDouble()
                            useOverride = true
                        } catch (e: Exception) {
                            android.util.Log.e("LLMManager", "Invalid location override format", e)
                        }
                    }

                    if (useOverride) {
                        val left = lon - 0.1
                        val bottom = lat - 0.1
                        val right = lon + 0.1
                        val top = lat + 0.1
                        bbox = "$bottom,$left,$top,$right"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val fullQuery = "[out:json][timeout:10];$overpassQuery($bbox);out 10;"
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
                        var elements = jsonObj.optJSONArray("elements") ?: org.json.JSONArray()
                        
                        if (elements.length() == 0 && isFood && isCuisineSpecific) {
                            // Fallback to generic restaurant if specific cuisine fails
                            val fallbackQuery = "[out:json][timeout:10];node[\"amenity\"~\"restaurant|fast_food\"]($bbox);out 10;"
                            val fbEncodedQuery = java.net.URLEncoder.encode(fallbackQuery, "UTF-8")
                            val fallbackUrl = java.net.URL("https://overpass-api.de/api/interpreter?data=$fbEncodedQuery")
                            val fallbackConn = fallbackUrl.openConnection() as java.net.HttpURLConnection
                            fallbackConn.connectTimeout = 10000
                            fallbackConn.readTimeout = 10000
                            fallbackConn.setRequestProperty("User-Agent", "GeminiNanoSample/1.0")
                            fallbackConn.requestMethod = "GET"
                            if (fallbackConn.responseCode == 200) {
                                val fallbackResponse = fallbackConn.inputStream.bufferedReader().use { it.readText() }
                                val fallbackObj = org.json.JSONObject(fallbackResponse)
                                elements = fallbackObj.optJSONArray("elements") ?: org.json.JSONArray()
                            }
                        }
                        val places = mutableListOf<String>()
                        val placesWithCoords = mutableListOf<Pair<String, String>>()
                        for (i in 0 until elements.length()) {
                            val element = elements.getJSONObject(i)
                            val tags = element.optJSONObject("tags") ?: continue
                            var name = tags.optString("name", tags.optString("name:en", "")).trim()
                            val brand = tags.optString("brand", tags.optString("brand:en", "")).trim()
                            
                            val lat = element.optDouble("lat")
                            val lon = element.optDouble("lon")
                            
                            if (name.isEmpty() && brand.isNotEmpty()) {
                                name = brand
                            }
                            
                            if (name.isNotEmpty() && !places.contains(name)) {
                                places.add(name)
                                placesWithCoords.add(Pair(name, "$lat,$lon"))
                                if (places.size >= 3) break
                            }
                        }
                        
                        // If we found elements but NONE of them had a name, use a generic fallback
                        if (places.isEmpty() && elements.length() > 0) {
                            val firstLat = elements.getJSONObject(0).optDouble("lat")
                            val firstLon = elements.getJSONObject(0).optDouble("lon")
                            if (isFuel) {
                                places.add("Local Gas Station")
                                placesWithCoords.add(Pair("Local Gas Station", "$firstLat,$firstLon"))
                            } else if (isFood) {
                                places.add("Local Restaurant")
                                placesWithCoords.add(Pair("Local Restaurant", "$firstLat,$firstLon"))
                            }
                        }
                        
                        if (places.isNotEmpty()) {
                            val placesStr = places.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString(", ")
                            val coordInstructions = placesWithCoords.mapIndexed { index, pair -> "If user chooses ${index + 1} (${pair.first}), output EXACTLY <TOOL>navigate(${pair.second})</TOOL>" }.joinToString(". ")
                            return@withContext "\n\n[System Note: Do NOT use any <TOOL> tags yet. You MUST reply to the user with EXACTLY this text. Do NOT translate the names, read them EXACTLY as written: \"I found these options nearby: $placesStr. Which one would you like to navigate to?\" INTERNAL RULE FOR NEXT TURN: $coordInstructions]"
                        }
                    }
                    return@withContext ""
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
        return ""
    }
    
    suspend fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        val dynCtx = getDynamicContext(context, query)
        val q = (query + " " + MemoryManager.getSlidingWindowContext(500)).lowercase()
        val userQuery = query.lowercase()
        val isHvac = userQuery.contains("temperature") || userQuery.contains("hot") || userQuery.contains("cold") || userQuery.contains("warm") || userQuery.contains("cool") || userQuery.contains("ac") || userQuery.contains("heater") || userQuery.contains("defroster") || userQuery.contains("increase") || userQuery.contains("decrease") || userQuery.contains("fog") || userQuery.contains("window")
        val isSightseeing = userQuery.contains("visit") || userQuery.contains("interesting") || userQuery.contains("places") || userQuery.contains("sightseeing") || userQuery.contains("tourist") || userQuery.contains("what to do") || userQuery.contains("where to go") || userQuery.contains("city")
        val isFoodQuery = userQuery.contains("hungry") || userQuery.contains("food") || userQuery.contains("eat") || userQuery.contains("restaurant") || userQuery.contains("italian") || userQuery.contains("mexican") || userQuery.contains("chinese") || userQuery.contains("pizza") || userQuery.contains("burger") || userQuery.contains("sushi") || userQuery.contains("indian") || userQuery.contains("thai") || userQuery.contains("japanese") || userQuery.contains("vegetarian") || userQuery.contains("vegan") || userQuery.contains("american") || userQuery.contains("fast food")
        val isFuelQuery = userQuery.contains("fuel") || userQuery.contains("gas") || userQuery.contains("petrol") || userQuery.contains("charging")
        
        val mem = MemoryManager.getSlidingWindowContext(200).lowercase()
        val isFollowUpToSearch = (mem.contains("would you like to navigate") || mem.contains("found these options nearby") || mem.contains("which places would you like to visit") || mem.contains("navigate to any of these options")) && !isFoodQuery && !isFuelQuery
        val isFollowUpToFuel = (mem.contains("navigate you to a nearby gas station") || mem.contains("find a nearby gas station") || mem.contains("nearby charging station")) && !isFoodQuery
        
        val isNav = (userQuery.contains("navigate") || userQuery.contains("go to") || userQuery.contains("directions") || userQuery.contains("route")) && !isSightseeing && !isFoodQuery || isFollowUpToSearch
        val isAmbient = userQuery.contains("home") || userQuery.contains("work")
        val isDiag = userQuery.contains("wrong") || userQuery.contains("broken") || userQuery.contains("issue") || userQuery.contains("light") || userQuery.contains("code") || userQuery.contains("door") || userQuery.contains("diagnos") || userQuery.contains("obd") || userQuery.contains("ob2") || userQuery.contains("engine") || userQuery.contains("service")
        val isWellness = userQuery.contains("pain") || userQuery.contains("hurt") || userQuery.contains("tired") || userQuery.contains("sore") || userQuery.contains("ache")
        val isMusic = userQuery.contains("music") || userQuery.contains("play") || userQuery.contains("song") || userQuery.contains("pause") || userQuery.contains("stop") || userQuery.contains("next") || userQuery.contains("previous")
        val isLocationKnowledge = userQuery.contains("where was") || userQuery.contains("filmed") || userQuery.contains("located") || userQuery.contains("location of") || userQuery.contains("address of")
        
        val isFood = isFoodQuery && !isFollowUpToSearch
        val isFuel = (isFuelQuery || isFollowUpToFuel) && !isFollowUpToSearch
        
        val basePrompt = StringBuilder()
        basePrompt.append("You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using the <TOOL>command()</TOOL> syntax. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.\n\n")
        
        val isComplexQuery = isHvac || isSightseeing || isFoodQuery || isFuelQuery || isNav || isDiag || isWellness || isAmbient || isLocationKnowledge
        
        if (isComplexQuery) {
            basePrompt.append("=== VEHICLE STATE ===\n")
            basePrompt.append("${VehicleManager.getLLMContextString(context)}\n")
            basePrompt.append("Memory: $userMemory\n\n")
        }
        
        basePrompt.append("=== TOOLS ===\n")
        basePrompt.append("${ToolManager.getLlmToolsPrompt(query)}\n\n")
        
        basePrompt.append("IMPORTANT: If you use a tool, YOU MUST ALWAYS say what you are doing FIRST, and then append the XML TAG '<TOOL>' at the very end of your response. Example: 'Playing relaxing music now. <TOOL>playMusic(relaxing music)</TOOL>'\n\n")
        
        if (isComplexQuery) {
            basePrompt.append("=== STRICT RULES ===\n")
            
            basePrompt.append("1. HVAC: To change the temperature, use the EXACT <TOOL> syntax AFTER your text:\n")
            basePrompt.append("- If user gives an exact number: \"I've set the temperature to [VAL] degrees. <TOOL>setTemperature(VAL)</TOOL>\"\n")
            basePrompt.append("- If user is cold or wants to increase it: \"I'm warming it up. <TOOL>increaseTemperature()</TOOL>\"\n")
            basePrompt.append("- If user is hot or wants to decrease it: \"I'm cooling it down. <TOOL>decreaseTemperature()</TOOL>\"\n")
            basePrompt.append("DO NOT mention the current temperature after using a tool, because your memory of it will be outdated!\n")

            basePrompt.append("2. WELLNESS: If the user complains about body pain, being tired, or their back hurting, DO NOT USE ANY TOOLS YET. You MUST ONLY ask: 'Would you like me to play some relaxing music, turn on the seat massager, or turn on the seat heater?'. Wait for the user's response. If the user says yes, output the EXACT syntax <TOOL>setSeatHeater(2)</TOOL>, <TOOL>setSeatMassager(2)</TOOL>, and <TOOL>playMusic(relaxing music)</TOOL> to activate what they requested.\n")

            basePrompt.append("3. NAVIGATION: To navigate, briefly acknowledge the destination and then use the syntax <TOOL>navigate(DEST)</TOOL> at the end. Example: \"Setting destination to Tokyo. <TOOL>navigate(Tokyo)</TOOL>\"\n")

            basePrompt.append("5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: \"Heading Home. Should I turn on the heater? <TOOL>navigate(Home)</TOOL>\"\n")

            basePrompt.append("6. SIGHTSEEING: If asked about a city, places to visit, or sightseeing, YOU MUST use your world knowledge to suggest places AND THEN YOU MUST END YOUR RESPONSE WITH THE EXACT QUESTION: \"Which places would you like to visit?\". Do NOT forget to ask this question!\n")
            basePrompt.append("6.5 SIGHTSEEING ON MAP: If the user EXPLICITLY asks to show places 'on map', you MUST use the tool <TOOL>search(QUERY)</TOOL> where QUERY is exactly what they asked for (e.g. <TOOL>search(best places to visit in tokyo)</TOOL>).\n")
            basePrompt.append("7. AMBIGUITY: If the user replies with a specific place from your list, you MUST use the <TOOL>navigate(DEST)</TOOL> tool to navigate there.\n")
            
            if (isFood || q.isEmpty()) {
                if (dynCtx.isNotEmpty()) {
                    basePrompt.append("8. FOOD CHOICES: $dynCtx\n")
                } else {
                    basePrompt.append("8. FOOD CHOICES: If the user is hungry and hasn't specified a cuisine, DO NOT use tools. Just ask them what kind of food they want.\n")
                }
            }
            
            if (isFuel || q.isEmpty()) {
                if (dynCtx.isNotEmpty()) {
                    basePrompt.append("9. FUEL/CHARGING CHOICES: $dynCtx\n")
                } else {
                    basePrompt.append("9. FUEL/CHARGING: If the user says they are out of fuel or battery, DO NOT USE ANY TOOLS. ALWAYS ask first EXACTLY: \"Should I find a nearby gas station?\"\n")
                }
            }
        }
        

        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty() && isComplexQuery) {
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

    suspend fun prewarm(context: Context) {
        if (engine == null || conversation == null || !isFirstMessage) return
        
        withContext(Dispatchers.IO) {
            isPrewarming = true
            try {
                Log.d("LLMManager", "Starting background pre-warm sequence...")
                val sysPrompt = getSystemPrompt(context, "")
                val prewarmPrompt = "$sysPrompt\n\n[System Initialization: Acknowledge this configuration. Do not generate a response.]"
                
                val latch = kotlinx.coroutines.sync.Mutex(true)
                conversation?.sendMessageAsync(Contents.of(Content.Text(prewarmPrompt)), object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {}
                    override fun onDone() { latch.unlock() }
                    override fun onError(throwable: Throwable) { latch.unlock() }
                }, emptyMap())
                
                latch.lock() // Suspend until the NPU finishes computing the KV cache
                isFirstMessage = false
                Log.d("LLMManager", "Prewarm complete. KV cache populated.")
            } catch (e: Exception) {
                Log.e("LLMManager", "Prewarm failed", e)
            } finally {
                isPrewarming = false
            }
        }
    }
}

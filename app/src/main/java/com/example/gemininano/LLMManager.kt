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
            // Enforce a minimum of 4096 tokens to prevent native SIGSEGV when HVAC system prompts get too large
            val maxTokens = Math.max(prefs.getInt("max_tokens", 4096), 4096)
            
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
        val isFollowUpToSearch = mem.contains("which one would you like to navigate") || mem.contains("found these options nearby")
        
        val isNav = q.contains("navigate") || q.contains("go to") || q.contains("directions") || q.contains("route") || q.contains("take me") || (q.length <= 2 && q.toIntOrNull() != null) || isFollowUpToSearch
        val isFood = (q.contains("hungry") || q.contains("food") || q.contains("eat") || q.contains("restaurant") || q.contains("italian") || q.contains("mexican") || q.contains("chinese") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("indian") || q.contains("thai") || q.contains("japanese") || q.contains("vegetarian") || q.contains("vegan")) && !isNav
        val isFuel = (q.contains("fuel") || q.contains("gas") || q.contains("petrol") || q.contains("charging")) && !isNav
        val isSightseeing = (q.contains("visit") || q.contains("interesting") || q.contains("places") || q.contains("sightseeing") || q.contains("tourist") || q.contains("what to do") || q.contains("where to go") || q.contains("city") || q.contains("see")) && !isNav
        
        if ((isFood || isFuel || isSightseeing) && prompt.length < 50) {
            try {
                if (isFuel) {
                    return "Context: The system has automatically found the following nearby gas stations: 1. ENEOS (Shinjuku), 2. Shell Station (Shibuya), 3. Cosmo Station (Minato). Do not list them unless asked. Ask the user if they want to navigate to one of these."
                }
                if (isFood) {
                    return "Context: The system found these nearby restaurants: 1. Olive Garden, 2. Mario's Italian, 3. Sushi Zanmai. Ask the user which one they would like to navigate to."
                }
                if (isSightseeing) {
                    return "Context: The system found these nearby sightseeing locations: 1. Tokyo Skytree, 2. Sensō-ji Temple, 3. Meiji Shrine. Ask the user which one they would like to visit."
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
        val isHvac = q.contains("temperature") || q.contains("hot") || q.contains("cold") || q.contains("warm") || q.contains("cool") || q.contains("ac") || q.contains("heater") || q.contains("defroster") || q.contains("increase") || q.contains("decrease") || q.contains("fog") || q.contains("window")
        val isSightseeing = q.contains("visit") || q.contains("interesting") || q.contains("places") || q.contains("sightseeing") || q.contains("tourist") || q.contains("what to do") || q.contains("where to go") || q.contains("city")
        val isFood = q.contains("hungry") || q.contains("food") || q.contains("eat") || q.contains("restaurant") || q.contains("italian") || q.contains("mexican") || q.contains("chinese") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("indian") || q.contains("thai")
        val isFuel = q.contains("fuel") || q.contains("gas") || q.contains("petrol") || q.contains("charging")
        val mem = MemoryManager.getSlidingWindowContext(200).lowercase()
        val isFollowUpToSearch = mem.contains("which one would you like to navigate") || mem.contains("found these options nearby")
        
        val isNav = (q.contains("navigate") || q.contains("go to") || q.contains("directions") || q.contains("route")) && !isSightseeing && !isFood || isFollowUpToSearch
        val isAmbient = q.contains("home") || q.contains("work")
        val isDiag = q.contains("wrong") || q.contains("broken") || q.contains("issue") || q.contains("light") || q.contains("code") || q.contains("door") || q.contains("diagnos") || q.contains("obd") || q.contains("ob2") || q.contains("engine") || q.contains("service")
        val isWellness = q.contains("pain") || q.contains("hurt") || q.contains("tired") || q.contains("sore") || q.contains("ache")
        val isMusic = q.contains("music") || q.contains("play") || q.contains("song") || q.contains("pause") || q.contains("stop") || q.contains("next") || q.contains("previous")
        val isLocationKnowledge = q.contains("where was") || q.contains("filmed") || q.contains("located") || q.contains("location of") || q.contains("address of")
        
        val basePrompt = StringBuilder()
        basePrompt.append("You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using the <TOOL>command()</TOOL> syntax. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.\n\n")
        
        basePrompt.append("=== VEHICLE STATE ===\n")
        basePrompt.append("${VehicleManager.getLLMContextString(context)}\n")
        basePrompt.append("Memory: $userMemory\n\n")
        
        basePrompt.append("=== TOOLS ===\n")
        basePrompt.append("${ToolManager.getLlmToolsPrompt(query)}\n\n")
        
        basePrompt.append("=== STRICT RULES ===\n")
        basePrompt.append("IMPORTANT: If you use a tool, YOUR RESPONSE MUST EXACTLY START WITH THE XML TAG '<TOOL>'. Do NOT omit it.\n")
        
        basePrompt.append("1. HVAC: To change the temperature, use the EXACT <TOOL> syntax BEFORE your text:\n")
        basePrompt.append("- If user gives an exact number: \"<TOOL>setTemperature(VAL)</TOOL> I've set the temperature to [VAL] degrees.\"\n")
        basePrompt.append("- If user is cold or wants to increase it: \"<TOOL>increaseTemperature()</TOOL> I'm warming it up.\"\n")
        basePrompt.append("- If user is hot or wants to decrease it: \"<TOOL>decreaseTemperature()</TOOL> I'm cooling it down.\"\n")
        basePrompt.append("DO NOT mention the current temperature after using a tool, because your memory of it will be outdated!\n")

        basePrompt.append("2. WELLNESS: If the user complains about body pain, being tired, or their back hurting, DO NOT USE ANY TOOLS YET. You MUST ONLY ask: 'Would you like me to play some relaxing music, turn on the seat massager, or turn on the seat heater?'. Wait for the user's response. If the user says yes, output the EXACT syntax <TOOL>setSeatHeater(2)</TOOL>, <TOOL>setSeatMassager(2)</TOOL>, and <TOOL>playMusic(relaxing music)</TOOL> to activate what they requested.\n")

        basePrompt.append("3. NAVIGATION: To navigate, you MUST reply ONLY with the EXACT syntax <TOOL>navigate(DEST)</TOOL> and NO other text. Example: \"<TOOL>navigate(Tokyo)</TOOL>\"\n")

        basePrompt.append("4. MULTI-TURN FUEL: If user mentions low fuel/range, you MUST ask: \"Should I find a nearby charging station?\" without any other text. DO NOT use the remember tool for fuel/diagnostics.\n")

        basePrompt.append("5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: \"<TOOL>navigate(Home)</TOOL> Should I turn on the heater?\"\n")

        basePrompt.append("6. SIGHTSEEING: If asked about a city, places to visit, or sightseeing, YOU MUST suggest places AND THEN YOU MUST END YOUR RESPONSE WITH THE EXACT QUESTION: \"Which places would you like to visit?\". Do NOT forget to ask this question!\n")
        basePrompt.append("7. AMBIGUITY: If the user replies with a specific place from your list, you MUST use the <TOOL>navigate(DEST)</TOOL> tool to navigate there.\n")
        val dyn = dynCtx
        if (dyn.isNotEmpty() && isFood) {
            basePrompt.append("8. FOOD CHOICES: $dyn\n")
        } else {
            basePrompt.append("8. FOOD CHOICES: If the user is hungry and hasn't specified a cuisine, DO NOT use tools. Just ask them what kind of food they want.\n")
        }
        val dynFuel = dynCtx
        if (dynFuel.isNotEmpty() && (q.contains("fuel") || q.contains("gas") || q.contains("petrol") || q.contains("charging"))) {
            basePrompt.append("9. FUEL/CHARGING CHOICES: $dynFuel\n")
        } else {
            basePrompt.append("9. DIAGNOSTICS: If asked about car problems, read the OBD code and ask if they want to call a mechanic.\n")
            basePrompt.append("10. FUEL/CHARGING: If the user says they are out of fuel or battery, ALWAYS ask first: \"Should I find a nearby gas station?\" DO NOT navigate immediately.\n")
        }
        if (isMusic || q.isEmpty()) {
            val musicQuery = q.replace("play", "").replace("music", "").replace("some", "").replace("for", "").replace("me", "").trim()
            val isSpecific = musicQuery.length > 2 && !q.contains("pause") && !q.contains("stop") && !q.contains("next") && !q.contains("previous")
            if (isSpecific) {
                basePrompt.append("11. MUSIC CHOICES: The user has specified what to play. Use the EXACT syntax <TOOL>playMusic($musicQuery)</TOOL> to play it.\n")
            } else {
                basePrompt.append("11. MUSIC CHOICES: The user asked to play music but didn't specify what. You MUST immediately use the EXACT syntax <TOOL>playMusic(relaxing music)</TOOL> to play default music.\n")
            }
        }
        if (isLocationKnowledge || q.isEmpty()) {
            basePrompt.append("12. LOCATION KNOWLEDGE: If the user asks where something is located or filmed (e.g., 'Where was Inception filmed?'), you MUST answer using your knowledge, output the EXACT syntax <TOOL>search(LOCATION_NAME)</TOOL> to drop a pin on the map, AND then ask 'Would you like to navigate there?'.\n")
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
            basePrompt.append("Assistant: <TOOL>decreaseTemperature()</TOOL> I'm cooling it down.\n")
            basePrompt.append("User: \"My windows are fogging up.\"\n")
            basePrompt.append("Assistant: <TOOL>turnOnDefroster()</TOOL> Activating front defogger.\n\n")
        }

        if (isSightseeing || q.isEmpty()) {
            basePrompt.append("[Sightseeing Query]\n")
            basePrompt.append("User: \"What are some sightseeing places to visit?\"\n")
            basePrompt.append("Assistant: There are many great places to visit around here! You should definitely see the Central Park and the Art Museum. Would you like me to navigate to any of these?\n\n")

            basePrompt.append("[Location Knowledge Query]\n")
            basePrompt.append("User: \"Where was the Hollywood movie Inception filmed in Tokyo?\"\n")
            basePrompt.append("Assistant: <TOOL>search(Ark Hills, Tokyo)</TOOL> The Hollywood movie Inception was filmed at Ark Hills in Tokyo. Would you like me to navigate there?\n\n")

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

        if (isFood || isFuel || isSightseeing || q.isEmpty()) {
            basePrompt.append("[Food Selection]\n")
            basePrompt.append("User: \"I'm hungry.\"\n")
            basePrompt.append("Assistant: Which food would you like to eat? e.g. Italian, Indian, Japanese, American, Pizza?\n")
            basePrompt.append("User: \"Italian.\"\n")
            basePrompt.append("Assistant: I found these options nearby: 1. Luigi's, 2. Roma. Which one would you like to navigate to?\n")
            basePrompt.append("User: \"1.\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Luigi's)</TOOL>\n\n")
            
            basePrompt.append("[Fuel/Charging Selection]\n")
            basePrompt.append("User: \"I need charging.\"\n")
            basePrompt.append("Assistant: I found these options nearby: 1. ChargePoint, 2. Tesla Supercharger. Which one would you like to navigate to?\n")
            basePrompt.append("User: \"The second one.\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Tesla Supercharger)</TOOL>\n\n")
        }

        if (isNav || q.isEmpty()) {
            basePrompt.append("[Direct Navigation]\n")
            basePrompt.append("User: \"Navigate to Tokyo\"\n")
            basePrompt.append("Assistant: <TOOL>navigate(Tokyo)</TOOL>\n\n")
        }

        if ((isFuel || q.isEmpty()) && dynCtx.isEmpty()) {
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

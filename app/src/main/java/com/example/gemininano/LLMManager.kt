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


    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    suspend fun autoInitialize(context: Context, force: Boolean = false, useCpu: Boolean = false, callback: InitCallback? = null) {
        if (!force && engine != null) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            val internalDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)
            val tmpDir = File("/data/local/tmp/")

            val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles(), tmpDir.listFiles())
                .flatMap { it.toList() }

            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedModelPath = prefs.getString("selected_model", null)
            val savedUseCpu = prefs.getBoolean("use_cpu", false)
            
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
                initialize(context, modelFile.absolutePath, force, useCpu || savedUseCpu, callback)
            } else {
                withContext(Dispatchers.Main) { callback?.onError(Exception("No model found")) }
            }
        }
    }

    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, useCpu: Boolean = false, callback: InitCallback? = null) {
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

                val backend = if (useCpu) Backend.CPU() else Backend.GPU()

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
                if (!useCpu) {
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
    
    fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        val q = query.lowercase()
        val isHvac = q.contains("temperature") || q.contains("hot") || q.contains("cold") || q.contains("warm") || q.contains("cool") || q.contains("ac") || q.contains("heater") || q.contains("defroster")
        val isSightseeing = q.contains("see") || q.contains("visit") || q.contains("interesting") || q.contains("places")
        val isFood = q.contains("hungry") || q.contains("food") || q.contains("eat") || q.contains("restaurant")
        val isNav = (q.contains("navigate") || q.contains("go to") || q.contains("directions") || q.contains("route")) && !isSightseeing && !isFood
        val isAmbient = q.contains("home") || q.contains("work")
        val isDiag = q.contains("wrong") || q.contains("broken") || q.contains("issue") || q.contains("light") || q.contains("code") || q.contains("door") || q.contains("fuel")
        
        val basePrompt = StringBuilder()
        basePrompt.append("You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using XML <TOOL> tags. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.\n\n")
        
        basePrompt.append("=== VEHICLE STATE ===\n")
        basePrompt.append("${VehicleManager.getLLMContextString(context)}\n")
        basePrompt.append("Memory: $userMemory\n\n")
        
        basePrompt.append("=== TOOLS ===\n")
        basePrompt.append("${ToolManager.getLlmToolsPrompt()}\n\n")
        
        basePrompt.append("=== STRICT RULES ===\n")
        
        if (isHvac || q.isEmpty()) {
            basePrompt.append("1. HVAC: To change the temperature, use the EXACT XML tag BEFORE your text:\n")
            basePrompt.append("- If user gives an exact number: \"<TOOL>setTemperature(VAL)</TOOL> I've set the temperature to [VAL] degrees.\"\n")
            basePrompt.append("- If user is cold or wants to increase it: \"<TOOL>increaseTemperature()</TOOL> I'm warming it up.\"\n")
            basePrompt.append("- If user is hot or wants to decrease it: \"<TOOL>decreaseTemperature()</TOOL> I'm cooling it down.\"\n")
            basePrompt.append("DO NOT mention the current temperature after using a tool, because your memory of it will be outdated!\n")
            basePrompt.append("2. WELLNESS: If the user complains about body pain, being tired, or their back hurting, you MUST ask if they want you to turn on the seat heater or seat massager as it might alleviate their pain. Example: \"I can turn on the seat heater and massager to help with your pain. Would you like me to do that?\"\n")
        }
        if (isNav || q.isEmpty()) {
            basePrompt.append("3. NAVIGATION: To navigate, you MUST reply ONLY with the EXACT XML tag <TOOL>navigate(DEST)</TOOL> and NO other text. Example: \"<TOOL>navigate(Tokyo)</TOOL>\"\n")
        }
        if (isDiag || q.isEmpty()) {
            basePrompt.append("4. MULTI-TURN FUEL: If user mentions low fuel/range, you MUST ask: \"Should I find a nearby charging station?\" without any other text.\n")
        }
        if (isAmbient || q.isEmpty()) {
            basePrompt.append("5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: \"<TOOL>navigate(Home)</TOOL> Should I turn on the heater?\"\n")
        }
        if (isFood || q.isEmpty()) {
            basePrompt.append("6. MEMORY: If asked for food, check User Food Preference in the Current State and automatically search the map for that type of food. Example: \"<TOOL>search(pure vegetarian restaurants)</TOOL>\"\n")
        }
        if (isSightseeing || q.isEmpty()) {
            basePrompt.append("7. SIGHTSEEING: If the user asks for places to visit, suggest 2-3 places and ALWAYS end your response by asking: \"Would you like me to navigate to any of these?\"\n")
            basePrompt.append("8. AMBIGUITY: If you suggest multiple places and the user agrees (e.g. \"Yes\") but does NOT specify which one, DO NOT use the navigate tool. You MUST ask \"Which one?\" first.\n")
        }
        if (isDiag || q.isEmpty()) {
            basePrompt.append("9. DIAGNOSTICS: If asked about car problems, read the OBD code and ask if they want to call a mechanic.\n")
        }

        basePrompt.append("\n")

        if (isHvac || q.isEmpty()) {
            basePrompt.append("[HVAC Control]\n")
            basePrompt.append("Input: \"Increase temperature.\"\n")
            basePrompt.append("Response: <TOOL>increaseTemperature()</TOOL> I'm warming it up.\n")
            basePrompt.append("Input: \"Set the temperature to 70.\"\n")
            basePrompt.append("Response: <TOOL>setTemperature(70)</TOOL> I've set the temperature to 70 degrees.\n")
            basePrompt.append("Input: \"I am feeling cold.\"\n")
            basePrompt.append("Response: <TOOL>increaseTemperature()</TOOL> I'm warming it up.\n")
            basePrompt.append("Input: \"Decrease temperature.\"\n")
            basePrompt.append("Response: <TOOL>decreaseTemperature()</TOOL> I'm cooling it down.\n\n")
        }

        if (isSightseeing || q.isEmpty()) {
            basePrompt.append("[Sightseeing - Accept]\n")
            basePrompt.append("Input: \"I'm driving through Paris. What are some interesting things I should see?\"\n")
            basePrompt.append("Response: Paris is beautiful! You should definitely see the Eiffel Tower and the Louvre Museum. Would you like me to navigate to any of these?\n")
            basePrompt.append("Input: \"Yes.\"\n")
            basePrompt.append("Response: Which place do you want to visit?\n")
            basePrompt.append("Input: \"The Louvre.\"\n")
            basePrompt.append("Response: <TOOL>navigate(Louvre Museum)</TOOL> Setting destination to the Louvre Museum.\n\n")

            basePrompt.append("[Sightseeing - Decline]\n")
            basePrompt.append("Input: \"What are some interesting things I should see along the way?\"\n")
            basePrompt.append("Response: You should definitely see the Eiffel Tower. Would you like me to navigate there?\n")
            basePrompt.append("Input: \"No\"\n")
            basePrompt.append("Response: OK, let me know if I can do something else for you.\n\n")
        }

        if (isNav || q.isEmpty()) {
            basePrompt.append("[Direct Navigation]\n")
            basePrompt.append("Input: \"Navigate to Tokyo\"\n")
            basePrompt.append("Response: <TOOL>navigate(Tokyo)</TOOL>\n\n")
        }

        if (isDiag || q.isEmpty()) {
            basePrompt.append("[Smart Fuel/Charging Routing]\n")
            basePrompt.append("Input: \"I am running out of fuel.\"\n")
            basePrompt.append("Response: Should I find a nearby charging station?\n")
            basePrompt.append("Input: \"Yes.\"\n")
            basePrompt.append("Response: <TOOL>navigate(charging station)</TOOL> Navigating to the nearest charging station.\n\n")
        }
            
        if (isAmbient || q.isEmpty()) {
            basePrompt.append("[Ambient Routine Confirmation]\n")
            basePrompt.append("Input: \"I'm heading home.\"\n")
            basePrompt.append("Response: <TOOL>navigate(Home)</TOOL> Navigating home. I noticed it's freezing outside. Would you like me to turn on the heater and seat warmers for your drive?\n")
            basePrompt.append("Input: \"Yes, please.\"\n")
            basePrompt.append("Response: <TOOL>setTemperature(72)</TOOL><TOOL>setSeatHeater(3)</TOOL>\n\n")
        }

        if (isFood || q.isEmpty()) {
            basePrompt.append("[Personalized Dining Search]\n")
            basePrompt.append("Input: \"I'm hungry.\"\n")
            basePrompt.append("Response: I remember you prefer pure vegetarian food. <TOOL>search(pure vegetarian restaurants nearby)</TOOL> Here are some pure vegetarian restaurants I found on the map.\n\n")
        }

        if (isDiag || q.isEmpty()) {
            basePrompt.append("[Contextual Diagnostics & Servicing]\n")
            basePrompt.append("Input: \"What's wrong with my car?\"\n")
            basePrompt.append("Response: Your check engine light is on with code P0420 (Catalytic Converter). Would you like me to call your preferred mechanic?\n")
            basePrompt.append("Input: \"Yes, call the mechanic.\"\n")
            basePrompt.append("Response: <TOOL>call(Mechanic)</TOOL>\n\n")

            basePrompt.append("[Door Alert Check]\n")
            basePrompt.append("Input: \"Check if any door is open.\"\n")
            basePrompt.append("Response: I checked the ADAS_OSE_DOOR_ALERT system. The current status is: All Doors Closed.\n\n")
        }
        
        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        var finalPrompt = basePrompt.toString()
        if (customInstructions.isNotEmpty()) {
            finalPrompt = finalPrompt.replace(
                "[Sightseeing - Accept]",
                "=== DYNAMIC VEHICLE SENSOR RULES ===\n" +
                customInstructions.mapIndexed { index, inst -> "${10 + index}. $inst" }.joinToString("\n") + "\n\n[Sightseeing - Accept]"
            )
        }
        
        return finalPrompt.trimIndent()
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
            if (context != null) {
                warmUpSystemPrompt(context)
            }
        } catch (e: Exception) {
            Log.e("LLMManager", "Failed to reset conversation", e)
        }
    }

    private fun warmUpSystemPrompt(context: Context) {
        if (engine == null || conversation == null) return
        
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("LLMManager", "Starting background warmup...")
                val systemPrompt = getSystemPrompt(context, "")
                
                val warmupText = "$systemPrompt\nUser: System initializing. Acknowledge with OK."
                
                isFirstMessage = false
                
                conversation!!.sendMessageAsync(
                    Contents.of(Content.Text(warmupText)),
                    object : com.google.ai.edge.litertlm.MessageCallback {
                        override fun onMessage(message: com.google.ai.edge.litertlm.Message) { }
                        override fun onDone() {
                            Log.d("LLMManager", "Warmup complete. KV cache populated.")
                        }
                        override fun onError(e: Throwable) {
                            Log.e("LLMManager", "Warmup failed", e)
                            isFirstMessage = true
                        }
                    },
                    emptyMap()
                )
            } catch (e: Exception) {
                Log.e("LLMManager", "Error during warmup", e)
                isFirstMessage = true
            }
        }
    }


}

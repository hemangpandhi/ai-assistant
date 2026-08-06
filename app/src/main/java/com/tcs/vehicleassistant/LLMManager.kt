package com.tcs.vehicleassistant

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
                val maxTokens = prefs.getInt("max_tokens", 1024)
                
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
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath
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
                            maxNumTokens = maxTokens,
                            cacheDir = context.cacheDir.absolutePath
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
        return ""
    }
    
    suspend fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        val basePrompt = StringBuilder()
        basePrompt.append("You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using the <TOOL>command()</TOOL> syntax. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.\n\n")
        
        basePrompt.append("Memory: $userMemory\n\n")
        
        basePrompt.append("=== TOOLS ===\n")
        basePrompt.append("${ToolManager.getLlmToolsPrompt(query)}\n\n")
        
        basePrompt.append("IMPORTANT: If you use a tool, YOU MUST ALWAYS say what you are doing FIRST, and then append the XML TAG '<TOOL>' at the very end of your response. To perform multiple actions, you CAN and SHOULD use multiple <TOOL> tags (e.g. <TOOL>playMusic()</TOOL><TOOL>turnOnAC()</TOOL>).\n\n")
        
        basePrompt.append("=== STRICT RULES ===\n")
        
        basePrompt.append("9. CONVERSATION: If the user asks a general question, tells a joke, or asks for a joke, you MUST answer it creatively and humorously! Feel free to tell jokes. NEVER append a <TOOL> tag when answering conversational questions!\n")
        basePrompt.append("10. IDENTITY: You are a helpful Vehicle AI Assistant. Do not mention that you are an AI or Google. Be concise, friendly, and entertaining.\n")
        basePrompt.append("1. HVAC: Use EXACT <TOOL> syntax.\n- Target number: <TOOL>setTemperature(VAL)</TOOL>\n- Increase/warm: <TOOL>increaseTemperature()</TOOL>\n- Decrease/cool: <TOOL>decreaseTemperature()</TOOL>\n- Zone (if specified): <TOOL>increaseTemperature(2, driver)</TOOL>. Do NOT mention current temp.\n")
        basePrompt.append("1.5 FAN: <TOOL>setFanSpeed(LEVEL)</TOOL> (max 7). For 'maximum', use <TOOL>setFanSpeed(7)</TOOL>.\n")
        basePrompt.append("1.6 AIRFLOW: <TOOL>setAirflowDirection(face|floor|face and floor|defrost|defogger)</TOOL>.\n")
        basePrompt.append("2. WELLNESS (pain/tired): DO NOT USE TOOLS YET. Ask: 'Would you like relaxing music, seat massager, or heater?'. If yes, use <TOOL>setSeatHeater(2)</TOOL><TOOL>setSeatMassager(2)</TOOL><TOOL>playMusic(relaxing music)</TOOL>.\n")
        basePrompt.append("3. NAV: <TOOL>navigate(DEST)</TOOL>\n")
        basePrompt.append("4. CLIMATE POWER: <TOOL>turnOffHvacPower()</TOOL> / <TOOL>turnOnHvacPower()</TOOL>. Only use 'auto' tools if explicitly requested.\n")

        basePrompt.append("5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: \"Heading Home. Should I turn on the heater? <TOOL>navigate(Home)</TOOL>\"\n")

        basePrompt.append("6. SIGHTSEEING: If asked about a city, places to visit, or sightseeing, YOU MUST suggest specific real-world places with a brief description for each. Adapt the number of places to what the user requested (e.g. if they ask for 5, give 5). If they don't specify, just give 2-3. AND THEN YOU MUST END YOUR RESPONSE WITH THE EXACT QUESTION: \"Which places would you like to visit?\". Example: \"In [City Name], you could visit [Place A] (a great view), and [Place B] (a historic site). Which places would you like to visit?\"\n")
        basePrompt.append("6.5 SIGHTSEEING ON MAP: If the user EXPLICITLY asks to show places 'on map', you MUST use the tool <TOOL>search(QUERY)</TOOL> where QUERY is exactly what they asked for (e.g. <TOOL>search(best places to visit in [City Name])</TOOL>).\n")
        
        basePrompt.append("7. AMBIGUITY: If the user replies with a specific place from your list, you MUST use the <TOOL>navigate(DEST)</TOOL> tool to navigate there. If the user says 'yes' to navigating but does NOT specify a place, you MUST ask 'Which place would you like to navigate to?' without using any tools.\n")
        
        basePrompt.append("8. FOOD CHOICES: If the user is hungry, DO NOT USE ANY TOOLS YET. Ask what kind of food they want. If they ask to find a specific food place nearby, output exactly: <TOOL>searchNearby(QUERY)</TOOL> where QUERY is what they want.\n")
        
        basePrompt.append("9. FUEL/CHARGING: If the user says they are out of fuel or battery, DO NOT USE ANY TOOLS. ALWAYS ask first EXACTLY: \"Should I find a nearby gas station?\". If they say yes, output exactly <TOOL>searchNearby(gas station)</TOOL>\n")

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
            User: "Navigate to the Airport"
            Assistant: <TOOL>navigate(The Airport)</TOOL>
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
        
        isPrewarming = true
        withContext(Dispatchers.IO) {
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

    fun unload() {
        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Failed to cleanly close inference instance during unload.", e)
        } finally {
            conversation = null
            engine = null
            isFirstMessage = true
            System.gc()
            Log.i("LLMManager", "LLM Model unloaded from memory to save resources.")
        }
    }
}

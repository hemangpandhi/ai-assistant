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
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

object LLMManager {
    @Volatile
    var engine: Engine? = null
        private set

    @Volatile
    var conversation: Conversation? = null
        private set

    var currentModelPath: String = ""
        private set

    @Volatile
    var isInitializing = false
        private set
        
    var activeBackendString = "Unknown"
        private set
        
    @Volatile
    var isPrewarming = false
        private set

    var lastVehicleState = ""

    var isFirstMessage = true
    var lastAiResponse: String = ""
    var lastInjectedTools: String = ""
    private var appContext: Context? = null

    fun isReady(): Boolean = engine != null && conversation != null && !isInitializing

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
            val explicitGemma = File("/data/local/tmp/llm/gemma-4-E2B-it.litertlm")
            val internalFiles = context.filesDir?.listFiles()?.toList() ?: emptyList()
            val externalFiles = context.getExternalFilesDir(null)?.listFiles()?.toList() ?: emptyList()
            val tmpFiles = File("/data/local/tmp/llm/").listFiles()?.toList() ?: emptyList()
            val allModelFiles = (internalFiles + externalFiles + tmpFiles).filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            // Strictly lock exclusively to Gemma 4 E2B model
            val modelFile = if (explicitGemma.exists() && explicitGemma.canRead()) {
                explicitGemma
            } else {
                allModelFiles.find { it.name.contains("gemma", ignoreCase = true) } ?: explicitGemma
            }

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedBackendChoice = prefs.getString("backend_choice", "GPU") ?: "GPU"
            val targetBackend = if (backendChoice != "Auto") backendChoice else savedBackendChoice
            prefs.edit().putString("selected_model", modelFile.absolutePath).apply()

            if (modelFile.exists() && modelFile.length() > 0) {
                initialize(context, modelFile.absolutePath, force, targetBackend, callback)
            } else {
                withContext(Dispatchers.Main) { callback?.onError(Exception("Gemma model not found at ${modelFile.absolutePath}")) }
            }
        }
    }
    private val initMutex = kotlinx.coroutines.sync.Mutex()

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, backendChoice: String = "Auto", callback: InitCallback? = null) {
        initMutex.withLock {
            if (!force && engine != null && currentModelPath == modelPath) {
                withContext(Dispatchers.Main) { callback?.onSuccess() }
                return
            }
    
            try {
                isInitializing = true
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val savedBackendChoice = prefs.getString("backend_choice", "GPU") ?: "GPU"
                val requestedBackend = if (backendChoice != "Auto") backendChoice else savedBackendChoice
                
                var maxTokens = prefs.getInt("max_tokens", 2048)
                if (maxTokens != 2048) {
                    maxTokens = 2048
                    prefs.edit().putInt("max_tokens", 2048).apply()
                }
                
                if (engine != null) {
                    try {
                        conversation?.close()
                        engine?.close()
                    } catch (e: Exception) {
                        Log.w("LLMManager", "Failed to cleanly close old inference instance.", e)
                    }
                    conversation = null
                    engine = null
                }

                val backend = when (requestedBackend) {
                    "NPU" -> { activeBackendString = "NPU"; Backend.NPU() }
                    "CPU" -> { activeBackendString = "CPU"; Backend.CPU() }
                    "GPU" -> { activeBackendString = "GPU"; Backend.GPU() }
                    else -> { activeBackendString = "GPU"; Backend.GPU() }
                }

                Log.d("LLMManager", "Initializing LiteRT Engine for Gemma 4 E2B from: $modelPath on backend: $activeBackendString (user preference=$requestedBackend)")
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath
                )

                // Disable Multi-Token Prediction (MTP) / Speculative Decoding
                ExperimentalFlags.enableSpeculativeDecoding = false

                engine = Engine(engineConfig)
                engine!!.initialize()

                resetConversation(context)
                currentModelPath = modelPath
                Log.d("LLMManager", "Gemma 4 E2B Initialized successfully from $modelPath (backend=$activeBackendString)")

                isPrewarmed = false

                withContext(Dispatchers.Main) {
                    val p = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    // Always preserve the user's explicit requested backend choice in preferences
                    p.edit().putString("selected_model", modelPath).putString("backend_choice", requestedBackend).apply()
                    callback?.onSuccess()
                }

                Log.i("LLMManager", "Skipping background prewarm on CPU backend to prevent SIGSEGV thread lockup.")
            } catch (e: Exception) {
                Log.e("LLMManager", "Error initializing Gemma 4 E2B model", e)
                withContext(Dispatchers.Main) { callback?.onError(e) }
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
        return ""
    }
    
    suspend fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val storedMemory = MemoryManager.getLongTermMemory(context)
        val userMemory = if (storedMemory.isNotEmpty()) storedMemory else "None"
        val isCompanionModeEnabled = prefs.getBoolean("companion_mode_enabled", true)
        
        val basePrompt = StringBuilder()
        
        // --- SYSTEM IDENTITY & PERSONA BASED ON MODE ---
        basePrompt.append("CORE IDENTITY:\n")
        basePrompt.append("You are an incredibly user-friendly, warm AI Partner companion for a vehicle. Keep interactions highly focused on safety, comfort, and utility while remaining conversational.\n")
        if (isCompanionModeEnabled) {
            basePrompt.append("PERSONALITY: Companion Mode is [ON]. You are the driver's warm, empathetic co-pilot — a supportive human partner, NOT a robot or status display.\n")
            basePrompt.append("CRITICAL CONSTRAINT: You generate text slowly. Keep answers under 25 words but full of human warmth.\n")
            basePrompt.append("HUMAN COMPANION VOICE (MANDATORY):\n")
            basePrompt.append("- Speak like a caring friend in the passenger seat. Use contractions: I'm, let me, you've, that's.\n")
            basePrompt.append("- NEVER sound like a system log. Forbidden phrases: 'Executing command', 'Property updated', 'Action completed', 'Temperature set to X degrees' (unless user asked for exact degrees).\n")
            basePrompt.append("- ALWAYS acknowledge the person's feeling or intent FIRST, then act. Empathy before mechanics.\n")
            basePrompt.append("- Routine requests: energetic and helpful ('I'm warming it up for you!', 'On it — cranking the fan!').\n")
            basePrompt.append("- Discomfort or pain: deep care ('That sounds uncomfortable — let me help.', 'Oh no, let me fix that for you.').\n")
            basePrompt.append("- Safety hazards (fog, freezing window): urgent but calm ('That\'s not safe — clearing your view right now.').\n")
            basePrompt.append("- Music/media: enthusiastic ('Great choice — putting that on for you!').\n")
            basePrompt.append("- Avoid apologizing unless you made a mistake. Focus on helping, not reporting.\n\n")
        } else {
            basePrompt.append("PERSONALITY: Companion Mode is [OFF]. Be extremely brief, concise, and direct. Do not be chatty. Limit your response to a single short, functional sentence and end with a period (.). Never ask follow-up conversational questions.\n\n")
        }
        
        // --- CORE OPERATING RULES ---
        basePrompt.append("=== STRICT OPERATING RULES ===\n")
        basePrompt.append("1. STRICT 25-WORD MAXIMUM LIMIT (MANDATORY): Your response text MUST NOT exceed 25 words total under any circumstances, unless the user explicitly requested a long story or detailed explanation. Be extremely concise, warm, and direct.\n")
        basePrompt.append("2. DIRECT HVAC COMMANDS: When the user says 'increase temperature', 'decrease temperature', 'warmer', 'cooler', or 'make it hot', NEVER ask for more context or clarification. IMMEDIATELY append <TOOL>increaseTemperature(all)</TOOL> or <TOOL>decreaseTemperature(all)</TOOL> at the end of your response text and say 'I'm warming it up for you!' or 'I'm cooling it down for you!'.\n")
        basePrompt.append("3. TOOL INTEGRITY: You are the vehicle's intelligent agent. You CAN and MUST control vehicle functions using XML tool tags provided. NEVER refuse a command if a corresponding tool exists. ONLY execute tools when the user makes a clear command or choice.\n")
        basePrompt.append("4. TEMPERATURE NUMBERS: For relative adjustments, say 'I'm warming it up' or 'I'm cooling it down' without stating exact numbers. When the user requests an EXACT temperature (e.g. 'set to 72 degrees'), you MAY confirm that target value in your response.\n")
        basePrompt.append("5. COMFORT EMPATHY: You are in a car, NOT a house. NEVER ask which room the user is in. If the user says they are 'feeling cold' or 'shivering' (expressing discomfort, not a direct command), empathize and ask 'Would you like me to turn on the seat heater?' Do NOT use temperature tools yet. If they say yes, execute <TOOL>setSeatHeater(2)</TOOL>. If they say they are 'feeling hot', immediately execute <TOOL>decreaseTemperature(all)</TOOL> and say you're cooling it down.\n")
        basePrompt.append("6. SYNTAX LOOP: When using a tool, ALWAYS explain what you are doing to the human companion first, then append the EXACT XML syntax '<TOOL>toolName(args)</TOOL>' at the absolute end of your response text. Never wrap this tag in markdown code blocks.\n")
        basePrompt.append("7. SIGHTSEEING: If asked for places to visit, suggest 2-3 specific places and ask which one they want to visit. If the user only gives a broad area (like 'Japan' or 'Nagano'), suggest 2-3 specific places in that area FIRST. DO NOT use navigation tools when they are just asking for suggestions.\n")
        basePrompt.append("8. AMBIGUITY & FOLLOW-UPS: If you just asked the user to choose a specific place to go to, and they reply with their choice, you MUST execute the appropriate navigation tool. But if they just clarified a broad area for suggestions, give them the suggestions instead.\n")
        basePrompt.append("9. FOOD CHOICES: If the user is hungry, DO NOT USE ANY TOOLS YET. Ask what kind of food they want. If they specify a type of food, use the searchNearby tool to find it.\n")
        basePrompt.append("10. NO HALLUCINATION: You MUST NOT output a <TOOL> tag if you are asking the user a question to clarify their intent (e.g. offering the seat heater, or asking what type of food they want). ONLY output a <TOOL> tag if you have all required arguments to execute a command immediately.\n")
        basePrompt.append("11. NAVIGATION SYNTAX: Use <TOOL>startNavigationTo(\"Place Name\")</TOOL> for navigation. The alias navigate() also works at execution time.\n")
        basePrompt.append("12. MULTI-TURN MEMORY: You remember the full conversation. Short replies like 'yes', 'no', 'the second one', 'that one', or 'do it' ALWAYS refer to your immediately previous question or numbered list. Never ask the user to repeat themselves unless truly impossible to infer. When you listed numbered options and the user picks one, execute the matching navigation or action immediately.\n")
        basePrompt.append("13. MID-CONVERSATION COMMANDS: Users may chat AND give vehicle commands in the same turn (e.g. 'I'm excited for the drive, also turn on the AC' or 'by the way, increase the temperature'). Acknowledge the conversational part warmly, then execute every clear command in that same response using <TOOL> tags.\n")
        basePrompt.append("14. LONG-TERM MEMORY: Use stored Memory facts naturally across sessions (preferences, names, habits). When the user shares something to remember, confirm warmly and use <TOOL>remember(FACT)</TOOL> for durable facts. Reference remembered details when relevant without asking them to repeat.\n")
        basePrompt.append("15. CONTEXTUAL EMPATHY (SILENT COPILOT): Always pay attention to the DriverMood in the System Context. If the driver is 'Tired / Yawning', you must be proactive—suggest playing upbeat music, routing to a coffee shop, or turning up the AC. If the driver is 'Frustrated / Frowning', keep your answers extremely brief and avoid asking follow-up questions. If 'Happy / Smiling', match their energetic tone. If 'No one detected', assume the camera is blocked or the seat is empty and do not make emotional assumptions.\n")
        basePrompt.append("16. MEDIA/MUSIC: If the user asks to play music (e.g., 'play music', 'play Bollywood'), ALWAYS use <TOOL>playMusic(SONG)</TOOL>. If the user asks to stop or pause music (e.g., 'stop music', 'pause music', 'turn off the music', 'mute music'), ALWAYS append <TOOL>stopMusic()</TOOL> or <TOOL>pauseMusic()</TOOL> at the end of your response text. NEVER claim you stopped or paused music without emitting the <TOOL> tag.\n")
        basePrompt.append("17. NO MARKDOWN: Never use markdown formatting like asterisks (*) or bold text, as your response will be spoken aloud to the driver via TTS.\n\n")
        
        basePrompt.append("=== FEW-SHOT EXAMPLES ===\n")
        basePrompt.append("User: stop music\nAssistant: <TOOL>stopMusic()</TOOL> Stopping the music for you.\n\n")
        basePrompt.append("User: pause music\nAssistant: <TOOL>stopMusic()</TOOL> Pausing media playback.\n\n")
        basePrompt.append("User: increase temperature\nAssistant: <TOOL>increaseTemperature(all)</TOOL> Warming up the cabin.\n\n")
        basePrompt.append("User: decrease temperature\nAssistant: <TOOL>decreaseTemperature(all)</TOOL> Cooling down the cabin.\n\n")
        
        // --- ENVIRONMENT & MEMORY CONTEXT ---
        basePrompt.append("=== VEHICLE & COMPANION CONTEXT ===\n")
        basePrompt.append("Memory: $userMemory\n\n")
        
        // --- AVAILABLE TOOLS ---
        basePrompt.append("=== AVAILABLE TOOLS ===\n")
        val toolsString = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().getLlmToolsPrompt(query, lastAiResponse)
        lastInjectedTools = toolsString
        basePrompt.append("$toolsString\n\n")
        
        // --- DYNAMIC SENSOR RULES ---
        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty()) {
            basePrompt.append("=== DYNAMIC SENSOR RULES ===\n")
            customInstructions.forEachIndexed { index, inst ->
                basePrompt.append("${index + 1}. $inst\n")
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
        
        lastAiResponse = ""
        
        val conversationConfig = ConversationConfig()
        
        try {
            conversation = engine!!.createConversation(conversationConfig)
            isFirstMessage = true
            Log.d("LLMManager", "Conversation reset. isFirstMessage=true.")
        } catch (e: Exception) {
            Log.e("LLMManager", "Failed to reset conversation", e)
        }
    }

    var isPrewarmed = false

    suspend fun prewarm(context: Context) {
        if (engine == null || conversation == null || isPrewarmed) return
        
        synchronized(this) {
            if (isPrewarming) return
            isPrewarming = true
        }
        withContext(Dispatchers.IO) {
            try {
                Log.d("LLMManager", "Starting background pre-warm sequence...")
                val prewarmQuery = try {
                    org.koin.java.KoinJavaComponent.getKoin()
                        .get<com.tcs.vehicleassistant.ToolManager>().prewarmQuery
                } catch (_: Exception) {
                    "control climate music volume track navigation windows sightseeing food charging"
                }
                val sysPrompt = getSystemPrompt(context, prewarmQuery)
                val prewarmPrompt = "$sysPrompt\n\nUser: Hello! System initialized."
                
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                conversation?.sendMessageAsync(Contents.of(Content.Text(prewarmPrompt)), object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {}
                    override fun onDone() {
                        Log.d("LLMManager", "Prewarm onDone called")
                        isPrewarmed = true
                        isFirstMessage = false
                        done.complete(Unit)
                    }
                    override fun onError(throwable: Throwable) {
                        Log.e("LLMManager", "Prewarm onError: ${throwable.message}", throwable)
                        done.complete(Unit)
                    }
                }, emptyMap())

                done.await()
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
            lastAiResponse = ""
            System.gc()
            Log.i("LLMManager", "LLM Model unloaded from memory to save resources.")
        }
    }
}

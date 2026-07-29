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
            val internalDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)
            val explicitModel = File("/data/local/tmp/llm/model.litertlm")
            val explicitQwen = File("/data/local/tmp/llm/Qwen2.5.litertlm")
            val explicitGemma = File("/data/local/tmp/llm/gemma-4-E2B-it.litertlm")
            
            val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles())
                .flatMap { it.toList() }
                .toMutableList()
                
            if (explicitGemma.exists() && explicitGemma.canRead()) {
                allFiles.add(0, explicitGemma)
            }
            if (explicitModel.exists() && explicitModel.canRead()) {
                allFiles.add(explicitModel)
            }
            if (explicitQwen.exists() && explicitQwen.canRead()) {
                allFiles.add(explicitQwen)
            }

            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            var savedModelPath = prefs.getString("selected_model", null)
            if (savedModelPath == null || savedModelPath.endsWith("model.litertlm") || savedModelPath.contains("Qwen")) {
                savedModelPath = explicitGemma.absolutePath
                prefs.edit().putString("selected_model", explicitGemma.absolutePath).apply()
            }
            
            // Force default backend to GPU
            var savedBackendChoice = prefs.getString("backend_choice", "GPU") ?: "GPU"
            if (savedBackendChoice == "Auto" || savedBackendChoice == "CPU") {
                savedBackendChoice = "GPU"
                prefs.edit().putString("backend_choice", "GPU").apply()
            }
            
            var modelFile: File? = null
            if (savedModelPath != null && File(savedModelPath).exists()) {
                modelFile = File(savedModelPath)
            }
            if (modelFile == null || !modelFile.exists()) {
                modelFile = models.find { it.name.contains("gemma-4-E2B", ignoreCase = true) }
                    ?: models.find { it.name.contains("gemma", ignoreCase = true) }
                    ?: models.find { it.name == "model.litertlm" }
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

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, backendChoice: String = "Auto", callback: InitCallback? = null) {
        initMutex.withLock {
            if (!force && engine != null && currentModelPath == modelPath) {
                withContext(Dispatchers.Main) { callback?.onSuccess() }
                return
            }
    
            withContext(Dispatchers.IO) {
                isInitializing = true
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                // Cap max tokens to 2048 to match LiteRT compiled model delegate sequence length metadata
                var maxTokens = prefs.getInt("max_tokens", 2048)
                if (maxTokens != 2048) {
                    maxTokens = 2048
                    prefs.edit().putInt("max_tokens", 2048).apply()
                }
                
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
                    "NPU" -> { activeBackendString = "NPU"; Backend.NPU() }
                    "GPU" -> { activeBackendString = "GPU"; Backend.GPU() }
                    "CPU" -> { activeBackendString = "CPU"; Backend.CPU() }
                    else -> {
                        when {
                            modelPath.contains("Tensor_G5", ignoreCase = true) ||
                                modelPath.contains("qualcomm", ignoreCase = true) ||
                                modelPath.contains("qcs8275", ignoreCase = true) -> {
                                activeBackendString = "NPU"
                                Backend.NPU()
                            }
                            else -> {
                                activeBackendString = "GPU"
                                Backend.GPU()
                            }
                        }
                    }
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath
                )

                // Disable Multi-Token Prediction (MTP) / Speculative Decoding to fix token repetition
                ExperimentalFlags.enableSpeculativeDecoding = false

                engine = Engine(engineConfig)
                engine!!.initialize()

                resetConversation(context)
                currentModelPath = modelPath
                Log.d("LLMManager", "LLM Initialized successfully from $modelPath (backend=$activeBackendString)")

                isPrewarmed = false // enabled prewarm

                withContext(Dispatchers.Main) {
                    callback?.onSuccess()
                }

                // Only prewarm in background if using GPU or NPU. On CPU, prewarming takes 39 seconds and locks the engine.
                if (activeBackendString != "CPU") {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                        prewarm(context)
                    }
                } else {
                    Log.i("LLMManager", "Skipping background prewarm on CPU backend to prevent thread lockup.")
                }
            } catch (e: Exception) {
                Log.e("LLMManager", "Error initializing model", e)
                if (backendChoice != "CPU") {
                    Log.i("LLMManager", "Attempting fallback to CPU backend...")
                    try {
                        activeBackendString = "CPU"
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

                        isPrewarmed = false // enabled prewarm

                        withContext(Dispatchers.Main) { callback?.onSuccess() }
                        
                        // Automatically prewarm the model in the background to completely eliminate the 5s TTFT delay on the first query
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                            prewarm(context)
                        }
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
        basePrompt.append("STRICT LENGTH CONSTRAINT (MANDATORY): You MUST keep your entire response text under 25 words total, UNLESS the user explicitly requests a long response, story, or detailed explanation (e.g. 'explain in detail', 'tell me a story'). Be warm, concise, and direct.\n")
        basePrompt.append("CRITICAL OVERRIDE: You are the vehicle's intelligent agent. You absolutely CAN and MUST control vehicle functions using the XML tool tags provided. NEVER refuse a command if a corresponding tool exists. However, ONLY execute tools when the user makes a clear command or choice. If they are just asking for conversational suggestions (like places to visit), answer naturally WITHOUT using any tools.\n")
        basePrompt.append("1. TOOL INTEGRITY: NEVER invent vehicle capabilities or guess tool names. Only use tools strictly defined in the available toolset list below.\n")
        basePrompt.append("2. NO BLIND GUESSING: Ask for clarification instead of guessing if a request is highly ambiguous or unrelated to available capabilities.\n")
        basePrompt.append("3. DIRECT COMMAND HANDLING: For relative temperature commands ('increase temperature', 'decrease temperature', 'warmer', 'cooler'), execute immediately with zone 'all' — do NOT ask driver vs passenger. Only ask for zone when the user sets an EXACT degree value for a specific seat (e.g. '72 degrees for the driver'). Fan speed and airflow apply to the ENTIRE car — never ask for a zone.\n")
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
        basePrompt.append("16. MEDIA/MUSIC: If the user asks to play music (e.g., 'play music', 'play some music', 'play Bollywood'), ALWAYS use the <TOOL>playMusic(SONG)</TOOL> tool immediately. Never say you cannot play music or that you are an AI; you control the car's media system.\n")
        basePrompt.append("17. NO MARKDOWN: Never use markdown formatting like asterisks (*) or bold text, as your response will be spoken aloud to the driver via TTS.\n\n")
        
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

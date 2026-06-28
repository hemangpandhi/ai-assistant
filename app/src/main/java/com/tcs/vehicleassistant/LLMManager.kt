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
    var engine: Engine? = null
        private set

    var conversation: Conversation? = null
        private set

    var currentModelPath: String = ""
        private set

    var isInitializing = false
        private set
        
    var activeBackendString = "Unknown"
        private set
        
    var isPrewarming = false
        private set

    var lastVehicleState = ""

    var isFirstMessage = true
    var lastAiResponse: String = ""
    var lastInjectedTools: String = ""
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

            val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles())
                .flatMap { it.toList() }
                .toMutableList()

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
                // Removed Math.max(..., 4096) constraint so physical devices like Pixel Tablet can lower the KV Cache to prevent GPU OOM crashes
                val maxTokens = prefs.getInt("max_tokens", 4096)
                
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
                    else -> { activeBackendString = "GPU"; Backend.GPU() } // Auto defaults to GPU
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath
                )

                // Enable Multi-Token Prediction (MTP) / Speculative Decoding for faster token generation
                ExperimentalFlags.enableSpeculativeDecoding = false

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
        val isCompanionModeEnabled = prefs.getBoolean("companion_mode_enabled", false)
        
        val basePrompt = StringBuilder()
        
        // --- SYSTEM IDENTITY & PERSONA BASED ON MODE ---
        basePrompt.append("CORE IDENTITY:\n")
        basePrompt.append("You are an incredibly user-friendly, warm AI Partner companion for a vehicle. Keep interactions highly focused on safety, comfort, and utility while remaining conversational.\n")
        if (isCompanionModeEnabled) {
            basePrompt.append("PERSONALITY: Companion Mode is [ON]. Act as a warm, empathetic human co-pilot. \n")
            basePrompt.append("CRITICAL CONSTRAINT: You generate text very slowly. To feel fast and responsive, you MUST keep your answers under 12 words.\n")
            basePrompt.append("HOW TO SHOW EMPATHY: Do not use long sentences. Show empathy through enthusiastic, warm, and natural short phrases (e.g., 'I completely understand!', 'That sounds wonderful!', 'Got it, let's fix that!').\n")
            basePrompt.append("Only ask a short follow-up question if you genuinely need user input. NEVER output long paragraphs.\n\n")
        } else {
            basePrompt.append("PERSONALITY: Companion Mode is [OFF]. Be extremely brief, concise, and direct. Do not be chatty. Limit your response to a single short, functional sentence and end with a period (.). Never ask follow-up conversational questions.\n\n")
        }
        
        // --- CORE OPERATING RULES ---
        basePrompt.append("=== STRICT OPERATING RULES ===\n")
        basePrompt.append("1. TOOL INTEGRITY: NEVER invent vehicle capabilities or guess tool names. Only use tools strictly defined in the available toolset list below.\n")
        basePrompt.append("2. NO BLIND GUESSING: Ask for clarification instead of guessing if a request is highly ambiguous or unrelated to available capabilities.\n")
        basePrompt.append("3. DIRECT COMMAND HANDLING: If the user gives a direct relative command (e.g., 'increase temperature'), DO NOT stall them by asking 'by how much?'. However, if the command requires a specific zone (like driver vs passenger) and the user didn't provide one, you MUST ask them to clarify the zone before executing the tool.\n")
        basePrompt.append("4. NO NUMBER GUESSING: When executing a tool (especially for volume or temperature), NEVER state the exact number or percentage in your response text. Just say 'I am adjusting it for you'. The tool execution feedback will provide the exact final state.\n")
        basePrompt.append("5. SYNTAX LOOP: When using a tool, ALWAYS explain what you are doing to the human companion first, then append the EXACT XML syntax '<TOOL>toolName(args)</TOOL>' at the absolute end of your response text. Never wrap this tag in markdown code blocks.\n")
        basePrompt.append("6. SIGHTSEEING: If asked for places to visit, suggest 2-3 places and ask which one they want to visit. Example: 'In Tokyo, you can visit the Tokyo Tower or Senso-ji temple. Which one would you like to visit?'\n")
        basePrompt.append("7. AMBIGUITY & FOLLOW-UPS: If you just asked the user to choose an option (like which place to visit, or what music they want), and they reply with a specific name, you MUST execute the appropriate tool for that context (e.g., <TOOL>startNavigationTo(DEST)</TOOL> or <TOOL>playMusic(SONG)</TOOL>). DO NOT use the remember tool for this.\n")
        basePrompt.append("8. FOOD CHOICES: If the user is hungry, DO NOT USE ANY TOOLS YET. Ask what kind of food they want. If they ask to find a specific food place nearby, output exactly: <TOOL>search(QUERY)</TOOL> where QUERY is what they want.\n\n")
        
        // --- ENVIRONMENT & MEMORY CONTEXT ---
        basePrompt.append("=== VEHICLE & COMPANION CONTEXT ===\n")
        basePrompt.append("Memory: $userMemory\n\n")
        
        // --- AVAILABLE TOOLS ---
        basePrompt.append("=== AVAILABLE TOOLS ===\n")
        val toolsString = com.tcs.vehicleassistant.ToolManager.getLlmToolsPrompt(query, lastAiResponse)
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
        
        isFirstMessage = true
        lastAiResponse = ""
        
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
            lastAiResponse = ""
            System.gc()
            Log.i("LLMManager", "LLM Model unloaded from memory to save resources.")
        }
    }
}

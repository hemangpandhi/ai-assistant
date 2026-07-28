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
                
            if (explicitQwen.exists() && explicitQwen.canRead()) {
                allFiles.add(0, explicitQwen)
            }
            if (explicitModel.exists() && explicitModel.canRead()) {
                allFiles.add(explicitModel)
            }
            if (explicitGemma.exists() && explicitGemma.canRead()) {
                allFiles.add(explicitGemma)
            }

            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedModelPath = prefs.getString("selected_model", null)
            val savedBackendChoice = prefs.getString("backend_choice", "Auto") ?: "Auto"
            
            var modelFile: File? = null
            if (savedModelPath != null && !savedModelPath.endsWith("model.litertlm")) {
                modelFile = File(savedModelPath)
            }
            if (modelFile == null || !modelFile.exists()) {
                modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                    ?: models.find { it.name.contains("qwen", ignoreCase = true) }
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
                // Set max tokens to 3072 to comfortably fit system prompt + context while remaining under memory limit
                var maxTokens = if (modelPath.contains("model.litertlm", ignoreCase = true)) 1024 else 2048
                
                try {
                    try {
                        conversation?.close()
                        engine?.close()
                    } catch (e: Exception) {
                        Log.w("LLMManager", "Failed to cleanly close old inference instance.", e)
                    }
                    conversation = null
                    engine = null
                    System.gc()

                val backend = when (backendChoice) {
                    "NPU" -> { activeBackendString = "NPU"; Backend.NPU() }
                    "CPU" -> { activeBackendString = "CPU"; Backend.CPU() }
                    "GPU" -> { activeBackendString = "GPU"; Backend.GPU() }
                    else -> {
                        if (modelPath.contains("model.litertlm", ignoreCase = true) || 
                            modelPath.contains("gemma", ignoreCase = true)) {
                            activeBackendString = "CPU"
                            Backend.CPU()
                        } else {
                            activeBackendString = "GPU"
                            Backend.GPU()
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

                // Disabled prewarm to prevent startup lockup and native thread collisions
                isPrewarmed = true

                withContext(Dispatchers.Main) {
                    callback?.onSuccess()
                }
            } catch (e: Exception) {
                Log.e("LLMManager", "Error initializing model on $activeBackendString: ${e.message}", e)
                try {
                    conversation?.close()
                    engine?.close()
                } catch (_: Exception) {}
                conversation = null
                engine = null
                System.gc()
                withContext(Dispatchers.Main) { callback?.onError(e) }
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
        
        // --- SYSTEM IDENTITY & PERSONA ---
        basePrompt.append("CORE IDENTITY:\n")
        basePrompt.append("You are the vehicle's warm, intelligent co-pilot AI assistant. Keep responses brief (under 20 words) and helpful.\n\n")
        
        // --- CORE OPERATING RULES ---
        basePrompt.append("=== STRICT OPERATING RULES ===\n")
        basePrompt.append("1. TOOL EXECUTION: When the user gives a command, execute the exact <TOOL>toolName(args)</TOOL> tag.\n")
        basePrompt.append("2. MUSIC COMMANDS: When asked to play music, output exactly: <TOOL>playMusic(popular music)</TOOL> (replace 'popular music' with requested song). Do not add any extra sentences.\n")
        basePrompt.append("3. CAMERA CONSTRAINT: NEVER use analyzeCabinState() unless the user explicitly asks you to look at them or check the cabin camera.\n")
        basePrompt.append("4. CLIMATE & COMFORT: You are in a car, NOT a house. NEVER ask which room the user is in. Use increaseTemperature(all) or decreaseTemperature(all) for HVAC.\n")
        basePrompt.append("5. DIRECT CONCISE VOICE: Do not invent any extra conversational context before or after executing a tool.\n")
        basePrompt.append("6. SYNTAX: Always append the EXACT XML syntax '<TOOL>toolName(args)</TOOL>' at the end of your response text.\n\n")
        
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
                val prewarmPrompt = "$sysPrompt\n\n[System Initialization: Acknowledge this configuration. Do not generate a response.]"
                
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

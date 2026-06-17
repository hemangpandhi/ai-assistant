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
                    // Set ADSP_LIBRARY_PATH so the Hexagon DSP can find the QNN native libraries
                    try {
                        Os.setenv("ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)
                    } catch (e: Exception) {
                        Log.w("LLMManager", "Failed to set ADSP_LIBRARY_PATH", e)
                    }
                    try {
                        conversation?.close()
                        engine?.close()
                    } catch (e: Exception) {
                        Log.w("LLMManager", "Failed to cleanly close old inference instance.", e)
                    }
                    conversation = null
                engine = null

                val backendStr = when (backendChoice) {
                    "NPU" -> "NPU"
                    "GPU" -> "GPU"
                    "CPU" -> "CPU"
                    else -> "GPU" // Auto defaults to GPU
                }
                
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

                Log.i("LLMManager", "Attempting to initialize LiteRT with $backendStr backend...")
                engine = Engine(engineConfig)
                engine!!.initialize()
                
                // Add library check log for NPU
                if (backendStr == "NPU") {
                    val qnnLib = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so")
                    val qnnDelegateLib = File(context.applicationInfo.nativeLibraryDir, "libQnnTFLiteDelegate.so")
                    if (qnnLib.exists() && qnnDelegateLib.exists()) {
                        Log.i("LLMManager", "SUCCESS: Qualcomm Hexagon DSP libraries detected. NPU is actively running on QNN Hexagon.")
                    } else {
                        Log.w("LLMManager", "WARNING: Selected NPU but Qualcomm Hexagon DSP libraries are MISSING in ${context.applicationInfo.nativeLibraryDir}. NPU may fall back to EdgeTPU or fail.")
                    }
                } else {
                    Log.i("LLMManager", "SUCCESS: Engine is actively running on $backendStr.")
                }

                resetConversation(context)
                currentModelPath = modelPath
                Log.d("LLMManager", "LLM Initialized successfully from $modelPath using $backendStr")
                
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
    suspend fun getSystemPrompt(context: android.content.Context, query: String = "", previousExecutedTools: Set<String> = emptySet()): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val customPrompt = prefs.getString("system_prompt", null)
        
        if (!customPrompt.isNullOrEmpty()) {
            return customPrompt
        }
        
        return getDefaultSystemPrompt(context, query, previousExecutedTools)
    }
    
    suspend fun getDefaultSystemPrompt(context: android.content.Context, query: String = "", previousExecutedTools: Set<String> = emptySet()): String {
        val basePrompt = StringBuilder()
        basePrompt.append("You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using the <TOOL>command()</TOOL> syntax. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.\\n\\n")
        
        // Universal Agentic Context Injection
        basePrompt.append("=== VEHICLE STATE ===\\n")
        basePrompt.append("${VehicleManager.getLLMContextString(context)}\\n\\n")
        
        basePrompt.append("=== TOOLS ===\\n")
        basePrompt.append("${ToolManager.getLlmToolsPrompt(context, query, previousExecutedTools)}\\n\\n")
        
        basePrompt.append("IMPORTANT: If you use a tool, YOU MUST ALWAYS say what you are doing FIRST, and then append the XML TAG '<TOOL>' at the very end of your response. Example: 'Playing relaxing music now. <TOOL>playMusic(relaxing music)</TOOL>'\\n\\n")
        
        basePrompt.append("=== STRICT RULES ===\\n")
        
        basePrompt.append("1. HVAC: To change the temperature, use the EXACT <TOOL> syntax AFTER your text:\\n")
        basePrompt.append("- If user gives an exact number: \"I've set the temperature to [VAL] degrees. <TOOL>setTemperature(VAL)</TOOL>\"\\n")
        basePrompt.append("- If user is cold or wants to increase it: \"I'm warming it up. <TOOL>increaseTemperature()</TOOL>\"\\n")
        basePrompt.append("- If user is hot or wants to decrease it: \"I'm cooling it down. <TOOL>decreaseTemperature()</TOOL>\"\\n")
        basePrompt.append("DO NOT mention the current temperature after using a tool, because your memory of it will be outdated!\\n")

        basePrompt.append("2. MEMORY PROACTIVITY: If you retrieve a memory using your tools about a special occasion (like a birthday or anniversary), proactively ask if they would like to plan a dinner or do something special to celebrate.\\n")

        basePrompt.append("3. WELLNESS: If the user complains about body pain, being tired, or their back hurting, DO NOT USE ANY TOOLS YET. You MUST ONLY ask: 'Would you like me to play some relaxing music, turn on the seat massager, or turn on the seat heater?'. Wait for the user's response. If the user says yes, output the EXACT syntax <TOOL>setSeatHeater(2)</TOOL>, <TOOL>setSeatMassager(2)</TOOL>, and <TOOL>playMusic(relaxing music)</TOOL> to activate what they requested.\\n")

        basePrompt.append("4. NAVIGATION: To navigate, briefly acknowledge the destination and then use the syntax <TOOL>navigate(DEST)</TOOL> at the end. Example: \"Setting destination to Tokyo. <TOOL>navigate(Tokyo)\"\\n")

        basePrompt.append("5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: \"Heading Home. Should I turn on the heater? <TOOL>navigate(Home)\"\\n")

        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty()) {
            basePrompt.append("\\n=== DYNAMIC VEHICLE SENSOR RULES ===\\n")
            customInstructions.forEachIndexed { index, inst ->
                basePrompt.append("${10 + index}. $inst\\n")
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

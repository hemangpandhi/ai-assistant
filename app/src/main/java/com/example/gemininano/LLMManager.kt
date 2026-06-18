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
        
        val globalInstructions = ToolManager.getGlobalSystemInstructions()
        if (globalInstructions.isNotEmpty()) {
            basePrompt.append(globalInstructions)
        }

        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty()) {
            basePrompt.append("\\n=== DYNAMIC VEHICLE SENSOR RULES ===\\n")
            customInstructions.forEachIndexed { index, inst ->
                basePrompt.append("${10 + index}. $inst\\n")
            }
        }
        return basePrompt.toString().trimIndent()
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
        // Disabled: Prewarming with an empty query bypasses Tool RAG and injects all 26+ tools 
        // into the KV Cache, causing NPU memory segmentation faults (SIGSEGV).
        return
    }
}

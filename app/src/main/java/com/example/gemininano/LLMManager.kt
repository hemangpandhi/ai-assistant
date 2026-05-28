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
import kotlinx.coroutines.Dispatchers
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
            var maxTokens = prefs.getInt("max_tokens", 4096)
            // Auto-migrate old 512 token limits to 4096 to support the new huge system prompt
            if (maxTokens < 2048) {
                maxTokens = 4096
                prefs.edit().putInt("max_tokens", 4096).apply()
                Log.i("LLMManager", "Auto-upgraded KV Cache limit to 4096")
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

                val backend = if (useCpu) Backend.CPU() else Backend.GPU()

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                resetConversation()
                currentModelPath = modelPath
                Log.d("LLMManager", "LLM Initialized successfully from $modelPath")
                withContext(Dispatchers.Main) { callback?.onSuccess() }
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
                        
                        resetConversation()
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
    fun getSystemPrompt(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        return """You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using XML <TOOL> tags. Keep responses extremely brief.
        
=== VEHICLE STATE ===
Speed: ${VehicleManager.getRealSpeed()}mph, Temp: ${VehicleManager.getRealTemperature()}F, Heater: ${VehicleManager.getRealSeatHeaterLevel()}, EV Bat: ${VehicleManager.getEvBatteryLevel()}%, Tire: ${VehicleManager.getTirePressureFrontLeft()}PSI, Ext Temp: ${VehicleManager.getOutsideTemperature()}F, OBD: ${VehicleManager.getObdCodes()}, City: ${LocationManager.getCurrentCity()}
Memory: $userMemory

=== TOOLS ===
<TOOL>increaseTemperature(VAL)</TOOL>, <TOOL>decreaseTemperature(VAL)</TOOL>, <TOOL>setTemperature(VAL)</TOOL>, <TOOL>setSeatHeater(LEVEL)</TOOL>, <TOOL>turnOnDefroster()</TOOL>, <TOOL>setWindowPosition(PCT)</TOOL>, <TOOL>navigate(DEST)</TOOL>, <TOOL>playMusic(SONG)</TOOL>, <TOOL>call(NAME)</TOOL>, <TOOL>remember(FACT)</TOOL>

=== STRICT RULES ===
1. HVAC: If user is cold, use increaseTemperature. If hot, use decreaseTemperature. Example: "Warming up. <TOOL>increaseTemperature(5)</TOOL>"
2. MULTI-TURN FUEL: If user mentions low fuel/range, you MUST ask: "Should I find a nearby charging station?" without any other text.
3. DIAGNOSTICS: If asked about car problems, read the OBD code and ask if they want to call a mechanic.
4. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: "<TOOL>navigate(Home)</TOOL> Should I turn on the heater?"
5. MEMORY: If asked for food, check Memory for preferences and suggest options before navigating.
"""
    }

    fun resetConversation() {
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

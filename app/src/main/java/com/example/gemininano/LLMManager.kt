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
                    maxNumTokens = 512
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
                            maxNumTokens = 512
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
    fun getSystemPrompt(): String {
        return "You are an in-car AI assistant. " +
               "Vehicle state: Speed ${VehicleManager.getRealSpeed()}mph, Temp ${VehicleManager.getRealTemperature()}F, Heater ${VehicleManager.getRealSeatHeaterLevel()}, Fuel ${VehicleManager.getFuelLevel()}%, Gear ${VehicleManager.getGearSelection()}, OBD Code P0420 (Check Engine Light ON).\n" +
               "If you need to perform an action, you MUST append a tool tag to your response. Example: 'I will lower the temperature. <TOOL>decreaseTemperature(5.0)</TOOL>'. " +
               "Valid tool tags are: <TOOL>increaseTemperature(VALUE)</TOOL>, <TOOL>decreaseTemperature(VALUE)</TOOL>, <TOOL>setTemperature(VALUE)</TOOL>, <TOOL>turnOnDefroster()</TOOL>, <TOOL>turnOffDefroster()</TOOL>, <TOOL>setSeatHeater(LEVEL)</TOOL>, <TOOL>setWindowPosition(PERCENTAGE)</TOOL>, <TOOL>navigate(DESTINATION)</TOOL>."
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

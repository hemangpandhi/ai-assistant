package com.example.gemininano

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LLMManager {
    var llmInference: LlmInference? = null
        private set

    var currentModelPath: String = ""
        private set

    var isInitializing = false
        private set

    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    suspend fun autoInitialize(context: Context, force: Boolean = false, useCpu: Boolean = false, callback: InitCallback? = null) {
        if (!force && llmInference != null) {
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
                // Prioritize Gemma if no saved preference
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
        if (!force && llmInference != null && currentModelPath == modelPath) {
            callback?.onSuccess()
            return // Already initialized with this model
        }

        withContext(Dispatchers.IO) {
            isInitializing = true
            try {
                try {
                    // Close existing if any
                    llmInference?.close()
                } catch (e: Exception) {
                    Log.w("LLMManager", "Failed to cleanly close old inference instance. It may be busy.", e)
                }
                llmInference = null

                val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    
                if (useCpu) {
                    optionsBuilder.setPreferredBackend(LlmInference.Backend.CPU)
                } else {
                    optionsBuilder.setPreferredBackend(LlmInference.Backend.GPU)
                }

                llmInference = LlmInference.createFromOptions(context.applicationContext, optionsBuilder.build())
                currentModelPath = modelPath
                Log.d("LLMManager", "LLM Initialized successfully from $modelPath")
                withContext(Dispatchers.Main) { callback?.onSuccess() }
            } catch (e: Exception) {
                Log.e("LLMManager", "Error initializing model", e)
                withContext(Dispatchers.Main) { callback?.onError(e) }
            } finally {
                isInitializing = false
            }
        }
    }
}

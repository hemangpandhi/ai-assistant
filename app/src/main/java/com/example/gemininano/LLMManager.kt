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

    suspend fun autoInitialize(context: Context, force: Boolean = false, callback: InitCallback? = null) {
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
            
            // Prioritize Gemma because SmolLM/Qwen BPE tokenizers are incompatible with MediaPipe's SentencePiece engine, causing duplicated/corrupted tokens.
            val modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                ?: models.find { it.name.contains("Qwen", ignoreCase = true) }
                ?: models.firstOrNull()

            if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                initialize(context, modelFile.absolutePath, force, callback)
            } else {
                withContext(Dispatchers.Main) { callback?.onError(Exception("No model found")) }
            }
        }
    }

    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, callback: InitCallback? = null) {
        if (!force && llmInference != null && currentModelPath == modelPath) {
            callback?.onSuccess()
            return // Already initialized with this model
        }

        withContext(Dispatchers.IO) {
            isInitializing = true
            try {
                // Close existing if any
                llmInference?.close()
                llmInference = null

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(4096)
                    .build()

                llmInference = LlmInference.createFromOptions(context.applicationContext, options)
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

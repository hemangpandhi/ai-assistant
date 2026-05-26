package com.example.gemininano

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun initialize(context: Context, modelPath: String, callback: InitCallback? = null) {
        if (llmInference != null && currentModelPath == modelPath) {
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

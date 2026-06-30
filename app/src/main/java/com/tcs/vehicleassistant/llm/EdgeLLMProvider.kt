package com.tcs.vehicleassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.tcs.vehicleassistant.LLMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class EdgeLLMProvider : ILLMProvider {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String = ""
    private var isFirstMessage = true
    private val initMutex = Mutex()
    private var isPrewarming = false

    override suspend fun initialize(context: Context, force: Boolean) {
        if (!force && engine != null) return

        withContext(Dispatchers.IO) {
            val internalDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)
            val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles())
                .flatMap { it.toList() }
            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedModelPath = prefs.getString("selected_model", null)
            val savedBackendChoice = prefs.getString("backend_choice", "Auto") ?: "Auto"
            
            var modelFile = savedModelPath?.let { File(it) }
            if (modelFile == null || !modelFile.exists()) {
                modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                    ?: models.find { it.name.contains("Qwen", ignoreCase = true) }
                    ?: models.firstOrNull()
            }

            if (modelFile != null && modelFile.exists()) {
                initializeEngine(context, modelFile.absolutePath, force, savedBackendChoice)
            } else {
                throw Exception("No Edge LLM model found on device.")
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun initializeEngine(context: Context, modelPath: String, force: Boolean, backendChoice: String) {
        initMutex.withLock {
            if (!force && engine != null && currentModelPath == modelPath) return
            
            try {
                conversation?.close()
                engine?.close()
            } catch (e: Exception) {}
            conversation = null
            engine = null

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val maxTokens = prefs.getInt("max_tokens", 4096)

            val backend = when (backendChoice) {
                "NPU" -> Backend.NPU()
                "GPU" -> Backend.GPU()
                "CPU" -> Backend.CPU()
                else -> Backend.GPU()
            }

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = maxTokens,
                cacheDir = context.cacheDir.absolutePath
            )

            ExperimentalFlags.enableSpeculativeDecoding = true
            engine = Engine(engineConfig)
            engine!!.initialize()
            
            resetConversation()
            currentModelPath = modelPath
            Log.d("EdgeLLMProvider", "Edge LLM Initialized successfully from $modelPath")
            
            // Port prewarming logic
            prewarm(context)
        }
    }

    private suspend fun prewarm(context: Context) {
        if (engine == null || conversation == null || !isFirstMessage) return
        
        synchronized(this) {
            if (isPrewarming) return
            isPrewarming = true
        }
        withContext(Dispatchers.IO) {
            try {
                val sysPrompt = LLMManager.getSystemPrompt(context, "")
                val prewarmPrompt = "$sysPrompt\n\n[System Initialization: Acknowledge this configuration. Do not generate a response.]"
                
                val latch = Mutex(true)
                conversation?.sendMessageAsync(Contents.of(Content.Text(prewarmPrompt)), object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {}
                    override fun onDone() { 
                        isFirstMessage = false
                        latch.unlock() 
                    }
                    override fun onError(throwable: Throwable) { 
                        latch.unlock() 
                    }
                }, emptyMap())
                latch.lock()
            } catch (e: Exception) {
                Log.e("EdgeLLMProvider", "Prewarm failed", e)
            } finally {
                isPrewarming = false
            }
        }
    }

    override suspend fun generateStream(
        context: Context,
        prompt: String,
        userQuery: String,
        onToken: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (engine == null || conversation == null) {
            onError(Exception("Edge LLM not initialized"))
            return
        }

        while (isPrewarming) {
            kotlinx.coroutines.delay(100)
        }

        val promptToUse = if (isFirstMessage) {
            isFirstMessage = false
            val sysPrompt = LLMManager.getSystemPrompt(context, userQuery)
            "$sysPrompt\n\n$prompt"
        } else {
            prompt
        }

        val responseBuilder = java.lang.StringBuilder()

        try {
            conversation!!.sendMessageAsync(
                Contents.of(Content.Text(promptToUse)),
                object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val textContent = message.contents?.contents?.firstOrNull() as? Content.Text
                        val text = textContent?.text ?: ""
                        responseBuilder.append(text)
                        onToken(responseBuilder.toString())
                    }

                    override fun onDone() {
                        onDone(responseBuilder.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        onError(Exception(throwable))
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
            onError(e)
        }
    }

    override fun unload() {
        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {}
        conversation = null
        engine = null
        isFirstMessage = true
    }

    override fun resetConversation() {
        try {
            conversation?.close()
        } catch (e: Exception) {}
        isFirstMessage = true
        if (engine != null) {
            conversation = engine!!.createConversation(ConversationConfig())
        }
    }

    override fun isReady(): Boolean = engine != null
}

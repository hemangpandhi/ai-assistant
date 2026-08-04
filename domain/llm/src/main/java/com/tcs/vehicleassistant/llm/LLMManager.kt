package com.tcs.vehicleassistant.llm

import com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.benchmark
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.DeviceCapabilities
import com.tcs.vehicleassistant.core.KernelCacheManager
import com.tcs.vehicleassistant.core.LocalModelResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

object LLMManager {
    private const val TAG = "LLMManager"

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

    var currentCloudModelName: String = "Gemini"

    /** True when the active backend differs from what the user asked for, after a fallback. */
    @Volatile
    var didFallBackFromRequestedBackend = false
        private set

    /** True when the last successful [startEngine] enabled speculative decoding / MTP. */
    @Volatile
    var speculativeDecodingActive = false
        private set

    var lastVehicleState = ""

    var isFirstMessage = true
    var nativeTurnsSinceReset = 0
    var lastAiResponse: String = ""
    var lastInjectedTools: String = ""

    /**
     * Held for the duration of an inference so [unload] and re-initialization cannot close the
     * native engine from under an in-flight `sendMessageAsync` callback.
     */
    private val inferenceLock = Any()

    /** Counts inferences currently inside the native engine, guarded by [inferenceLock]. */
    private var activeInferences = 0

    fun isReady(): Boolean = engine != null && conversation != null && !isInitializing

    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    /**
     * Marks an inference as entering the native engine and returns the conversation to use, or
     * `null` when the engine is not usable. Callers must pair this with [endInference].
     */
    fun beginInference(): Conversation? = synchronized(inferenceLock) {
        val active = conversation
        if (active == null || engine == null || isInitializing) return@synchronized null
        activeInferences++
        active
    }

    fun endInference() = synchronized(inferenceLock) {
        if (activeInferences > 0) activeInferences--
    }

    /** True while at least one inference is inside the native engine. */
    fun hasActiveInference(): Boolean = synchronized(inferenceLock) { activeInferences > 0 }

    /**
     * Waits until no inference is inside the native engine, or [timeoutMs] elapses.
     * @return true when the engine is idle and safe to close/re-init.
     */
    suspend fun awaitInferenceDrain(
        timeoutMs: Long = AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (hasActiveInference()) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "Inference drain timed out with activeInferences still > 0")
                return false
            }
            delay(50)
        }
        return true
    }

    suspend fun autoInitialize(
        context: Context,
        force: Boolean = false,
        backendChoice: String = AssistantConfig.Backend.AUTO,
        callback: InitCallback? = null
    ) {
        if (!force && engine != null) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val savedModelPath = prefs.getString(AssistantConfig.Prefs.SELECTED_MODEL, null)
            val internalFiles = context.filesDir?.listFiles()?.toList() ?: emptyList()
            val externalFiles = context.getExternalFilesDir(null)?.listFiles()?.toList() ?: emptyList()
            val tmpFiles = File("/data/local/tmp/llm/").listFiles()?.toList() ?: emptyList()
            val allModelFiles = (internalFiles + externalFiles + tmpFiles).filter {
                it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm")
            }

            // Prefer OEM/user selected local model; fall back to default edge path / candidates.
            val modelFile = LocalModelResolver.resolve(
                savedPath = savedModelPath,
                candidates = allModelFiles,
            )

            val savedBackendChoice = prefs.getString(AssistantConfig.Prefs.BACKEND_CHOICE, AssistantConfig.Backend.AUTO)
                ?: AssistantConfig.Backend.AUTO
            val targetBackend = if (backendChoice != AssistantConfig.Backend.AUTO) backendChoice else savedBackendChoice
            prefs.edit().putString(AssistantConfig.Prefs.SELECTED_MODEL, modelFile.absolutePath).apply()

            if (modelFile.exists() && modelFile.length() > 0) {
                initialize(context, modelFile.absolutePath, force, targetBackend, callback)
            } else {
                withContext(Dispatchers.Main) {
                    callback?.onError(
                        Exception("Local model not found at ${modelFile.absolutePath}"),
                    )
                }
            }
        }
    }

    private val initMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Brings up the LiteRT engine, walking [DeviceCapabilities.backendFallbackChain] so a GPU
     * request on hardware without a working OpenCL driver degrades to CPU instead of leaving the
     * assistant permanently unusable.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun initialize(
        context: Context,
        modelPath: String,
        force: Boolean = false,
        backendChoice: String = AssistantConfig.Backend.AUTO,
        callback: InitCallback? = null
    ) {
        initMutex.withLock {
            if (!force && engine != null && currentModelPath == modelPath) {
                withContext(Dispatchers.Main) { callback?.onSuccess() }
                return
            }

            try {
                isInitializing = true

                if (engine != null && !awaitInferenceDrain()) {
                    if (force) {
                        Log.w(TAG, "Force re-init requested while inference active; forcibly resetting activeInferences to prevent deadlock.")
                        synchronized(inferenceLock) { activeInferences = 0 }
                    } else {
                        val busy = Exception("Model is still generating — refusing forced re-init")
                        Log.e(TAG, busy.message!!)
                        withContext(Dispatchers.Main) { callback?.onError(busy) }
                        return
                    }
                }

                val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val savedBackendChoice = prefs.getString(AssistantConfig.Prefs.BACKEND_CHOICE, AssistantConfig.Backend.AUTO)
                    ?: AssistantConfig.Backend.AUTO
                val requestedBackend =
                    if (backendChoice != AssistantConfig.Backend.AUTO) backendChoice else savedBackendChoice

                // maxNumTokens is pinned: larger windows overflow the KV cache on this hardware.
                val maxTokens = AssistantConfig.Llm.MAX_NUM_TOKENS
                if (prefs.getInt(AssistantConfig.Prefs.MAX_TOKENS, maxTokens) != maxTokens) {
                    prefs.edit().putInt(AssistantConfig.Prefs.MAX_TOKENS, maxTokens).apply()
                }

                closeEngineLocked()

                Log.i(TAG, "Accelerator probe: ${DeviceCapabilities.describe(context)}")
                val chain = DeviceCapabilities.backendFallbackChain(requestedBackend)
                Log.i(TAG, "Backend chain for request '$requestedBackend': $chain")

                var lastError: Exception? = null
                var initializedBackend: String? = null

                for (candidate in chain) {
                    try {
                        startEngine(context, modelPath, candidate, maxTokens)
                        initializedBackend = candidate
                        break
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Backend '$candidate' failed to initialize; trying next in $chain", e)
                        closeEngineLocked()
                        // Kernels serialized by a half-initialized GPU context are not reusable.
                        KernelCacheManager.invalidate(context)
                    }
                }

                if (initializedBackend == null) {
                    throw lastError ?: Exception("No usable LiteRT backend on this device")
                }

                activeBackendString = initializedBackend
                didFallBackFromRequestedBackend =
                    requestedBackend != AssistantConfig.Backend.AUTO && requestedBackend != initializedBackend

                resetConversation(context)
                currentModelPath = modelPath
                isPrewarmed = false

                Log.i(
                    TAG,
                    "Engine ready: model=$modelPath backend=$initializedBackend " +
                        "requested=$requestedBackend speculative=$speculativeDecodingActive " +
                        "kernelCacheWarm=${KernelCacheManager.isWarm(context)} " +
                        "kernelCacheBytes=${KernelCacheManager.sizeBytes(context)}"
                )

                withContext(Dispatchers.Main) {
                    prefs.edit()
                        .putString(AssistantConfig.Prefs.SELECTED_MODEL, modelPath)
                        // Preserve the user's stated preference; record separately what actually ran.
                        .putString(AssistantConfig.Prefs.BACKEND_CHOICE, requestedBackend)
                        .putString(AssistantConfig.Prefs.RESOLVED_BACKEND, initializedBackend)
                        .apply()
                    callback?.onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Gemma 4 E2B model", e)
                withContext(Dispatchers.Main) { callback?.onError(e) }
            } finally {
                isInitializing = false
            }
        }
    }

    /** Configures and initializes a LiteRT engine on a single [backendName]. Throws on failure. */
    @OptIn(ExperimentalApi::class)
    private fun startEngine(context: Context, modelPath: String, backendName: String, maxTokens: Int) {
        val backend = when (backendName) {
            AssistantConfig.Backend.NPU -> Backend.NPU()
            AssistantConfig.Backend.CPU -> Backend.CPU(threadCount = DeviceCapabilities.cpuCoreCount())
            else -> Backend.GPU()
        }

        // Persistent cache dir so compiled OpenCL kernels survive reboots and storage cleanups;
        // Context.cacheDir is evictable, which forced a full kernel recompile on every cold start.
        val cacheDir = KernelCacheManager.prepare(context, modelPath, backendName)

        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val wantSpeculative = prefs.getBoolean(AssistantConfig.Prefs.ENABLE_SPECULATIVE_DECODING, false)
        val enableSpeculative = wantSpeculative &&
            backendName != AssistantConfig.Backend.NPU &&
            modelSupportsSpeculativeDecoding(modelPath)

        Log.d(
            TAG,
            "Starting LiteRT engine model=$modelPath backend=$backendName cacheDir=$cacheDir " +
                "speculativeWanted=$wantSpeculative speculative=$enableSpeculative"
        )

        // Gallery: set ExperimentalFlags only around Engine construction + initialize(), then clear.
        ExperimentalFlags.enableSpeculativeDecoding = enableSpeculative
        speculativeDecodingActive = false

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = maxTokens,
            cacheDir = cacheDir
        )

        val created = Engine(engineConfig)
        try {
            created.initialize()
            speculativeDecodingActive = enableSpeculative
        } catch (e: Throwable) {
            try {
                created.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "Failed to close engine after failed initialize", closeError)
            }
            throw if (e is Exception) e else Exception(e)
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
        engine = created
    }

    /** Capability probe matching Gallery's [Capabilities.hasSpeculativeDecodingSupport] gate. */
    private fun modelSupportsSpeculativeDecoding(modelPath: String): Boolean {
        return try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (e: Exception) {
            Log.w(TAG, "Speculative-decoding capability probe failed for $modelPath", e)
            false
        }
    }

    /**
     * Gallery-aligned conversation options: sampler on GPU/CPU, stable system instruction.
     * Query-specific tools still ride on each user turn via [AgentOrchestrator].
     */
    @OptIn(ExperimentalApi::class)
    private fun buildConversationConfig(userQuery: String = ""): ConversationConfig {
        val sampler = if (speculativeDecodingActive) {
            null
        } else if (activeBackendString == AssistantConfig.Backend.NPU) {
            null
        } else {
            SamplerConfig(
                topK = AssistantConfig.Llm.SAMPLER_TOP_K,
                topP = AssistantConfig.Llm.SAMPLER_TOP_P,
                temperature = AssistantConfig.Llm.SAMPLER_TEMPERATURE,
            )
        }
        val systemInstruction = Contents.of(
            Content.Text(
                "You are the in-vehicle AI co-pilot with live cabin, media, and navigation tools. "
            )
        )
        
        val toolSchemaGenerator = org.koin.java.KoinJavaComponent.getKoin().get<ToolSchemaGenerator>()
        val schemas = toolSchemaGenerator.getOpenApiSchemas(userQuery)
        val providers = schemas.map { schema ->
            com.google.ai.edge.litertlm.tool(DynamicOpenApiTool(schema.second))
        }
        
        return ConversationConfig(
            systemInstruction = systemInstruction,
            samplerConfig = sampler,
            tools = providers
        )
    }

    /**
     * Runs LiteRT's official [benchmark] API (prefill/decode tok/s + TTFT). Blocks for tens of
     * seconds — call off the UI thread. Does not disturb the live [engine].
     */
    @OptIn(ExperimentalApi::class)
    suspend fun runOfficialBenchmark(
        context: Context,
        prefillTokens: Int = AssistantConfig.Llm.BENCHMARK_PREFILL_TOKENS,
        decodeTokens: Int = AssistantConfig.Llm.BENCHMARK_DECODE_TOKENS,
    ): String = withContext(Dispatchers.IO) {
        val modelPath = currentModelPath.ifBlank {
            context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AssistantConfig.Prefs.SELECTED_MODEL, null)
                .orEmpty()
        }
        if (modelPath.isBlank() || !File(modelPath).canRead()) {
            return@withContext "Benchmark skipped: no readable model path"
        }
        val backendName = activeBackendString.takeIf { it != "Unknown" }
            ?: AssistantConfig.Backend.GPU
        val backend = when (backendName) {
            AssistantConfig.Backend.NPU -> Backend.NPU()
            AssistantConfig.Backend.CPU -> Backend.CPU(threadCount = DeviceCapabilities.cpuCoreCount())
            else -> Backend.GPU()
        }
        val cacheDir = File(context.cacheDir, "benchmark_${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            val info = benchmark(
                modelPath = modelPath,
                backend = backend,
                prefillTokens = prefillTokens,
                decodeTokens = decodeTokens,
                cacheDir = cacheDir.absolutePath,
            )
            val summary =
                "init=${"%.2f".format(info.initTimeInSecond)}s " +
                    "TTFT=${"%.2f".format(info.timeToFirstTokenInSecond)}s " +
                    "prefill=${"%.1f".format(info.lastPrefillTokensPerSecond)} tok/s " +
                    "decode=${"%.1f".format(info.lastDecodeTokensPerSecond)} tok/s " +
                    "(prefillTokens=${info.lastPrefillTokenCount}, decodeTokens=${info.lastDecodeTokenCount})"
            Log.i(TAG, "LiteRT benchmark ($backendName): $summary")
            summary
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT benchmark failed", e)
            "Benchmark failed: ${e.message}"
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    /** Closes the engine and conversation. Caller must hold [initMutex] and have drained inferences. */
    private fun closeEngineLocked() {
        synchronized(inferenceLock) {
            if (activeInferences > 0) {
                // Should be unreachable after awaitInferenceDrain(); refuse rather than corrupt GPU state.
                throw IllegalStateException(
                    "Refusing to close LiteRT engine with $activeInferences active inference(s)"
                )
            }
            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close conversation", e)
            }
            try {
                engine?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close engine", e)
            }
            conversation = null
            engine = null
            activeInferences = 0
        }
    }

    /**
     * Recycles the LiteRT conversation to bound KV-cache growth.
     *
     * @return false when an inference is still in flight — the caller should wait and retry rather
     * than force-closing the conversation under a live stream.
     */
    fun resetConversation(context: Context? = null, userQuery: String = ""): Boolean {
        synchronized(inferenceLock) {
            if (engine == null) {
                isFirstMessage = true
                nativeTurnsSinceReset = 0
                return true
            }
            if (activeInferences > 0) {
                Log.w(TAG, "Skipping conversation reset: $activeInferences inference(s) still in flight")
                return false
            }

            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous conversation", e)
            }
            conversation = null
            lastAiResponse = ""

            try {
                conversation = engine?.createConversation(buildConversationConfig(userQuery))
                isFirstMessage = true
                nativeTurnsSinceReset = 0
                Log.d(
                    TAG,
                    "Conversation reset. isFirstMessage=true backend=$activeBackendString " +
                        "speculativeWas=$speculativeDecodingActive"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset conversation", e)
                return false
            }
            return true
        }
    }

    var isPrewarmed = false

    /**
     * Releases the native engine to reclaim RAM.
     *
     * Refuses to close while an inference is still inside the engine — memory-pressure callbacks
     * and session teardown could otherwise free native state that a streaming callback was about
     * to touch, crashing the process.
     */
    fun unload(): Boolean {
        synchronized(inferenceLock) {
            if (activeInferences > 0) {
                Log.i(TAG, "Skipping unload: $activeInferences inference(s) still in flight.")
                return false
            }
            try {
                conversation?.close()
                engine?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanly close inference instance during unload.", e)
            } finally {
                conversation = null
                engine = null
                isFirstMessage = true
                nativeTurnsSinceReset = 0
                isPrewarmed = false
                lastAiResponse = ""
                activeInferences = 0
                speculativeDecodingActive = false
                Log.i(TAG, "LLM model unloaded from memory to save resources.")
            }
            return true
        }
    }

    private class DynamicOpenApiTool(private val jsonString: String) : com.google.ai.edge.litertlm.OpenApiTool {
        override fun getToolDescriptionJsonString(): String = jsonString
        override fun execute(args: String): String {
            return "{}"
        }
    }
}

package com.tcs.vehicleassistant

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.KernelCacheManager
import com.tcs.vehicleassistant.llm.EngineStatusStore
import com.tcs.vehicleassistant.llm.LiteRtEngineHost
import com.tcs.vehicleassistant.llm.LlmBenchmarkRunner
import com.tcs.vehicleassistant.llm.LlmConversationSession
import com.tcs.vehicleassistant.llm.LlmInferenceGate
import com.tcs.vehicleassistant.llm.LlmModelLocator
import com.tcs.vehicleassistant.llm.SystemPromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Compatibility facade over SRP collaborators:
 * - [LlmInferenceGate] — inference vs teardown serialization
 * - [LiteRtEngineHost] — native engine create / fallback / close
 * - [LlmConversationSession] — conversation reset + OpenAPI tool binding
 * - [SystemPromptBuilder] — identity / tool / memory prompt assembly
 * - [LlmModelLocator] — on-device model discovery
 * - [LlmBenchmarkRunner] — official LiteRT benchmark
 * - [EngineStatusStore] — readiness + turn metadata
 *
 * Call sites keep using this object; new code should prefer the focused types above.
 */
object LLMManager {
    private const val TAG = "LLMManager"

    private val initMutex = Mutex()

    val engine: Engine?
        get() = LiteRtEngineHost.engine

    val conversation: Conversation?
        get() = LlmConversationSession.conversation

    var currentModelPath: String
        get() = EngineStatusStore.currentModelPath
        private set(value) {
            EngineStatusStore.currentModelPath = value
        }

    var isInitializing: Boolean
        get() = EngineStatusStore.isInitializing
        private set(value) {
            EngineStatusStore.isInitializing = value
        }

    var activeBackendString: String
        get() = EngineStatusStore.activeBackendString
        private set(value) {
            EngineStatusStore.activeBackendString = value
        }

    /** True when the active backend differs from what the user asked for, after a fallback. */
    var didFallBackFromRequestedBackend: Boolean
        get() = EngineStatusStore.didFallBackFromRequestedBackend
        private set(value) {
            EngineStatusStore.didFallBackFromRequestedBackend = value
        }

    /** True when the last successful [startEngine] enabled speculative decoding / MTP. */
    var speculativeDecodingActive: Boolean
        get() = EngineStatusStore.speculativeDecodingActive
        private set(value) {
            EngineStatusStore.speculativeDecodingActive = value
        }

    var lastVehicleState: String
        get() = EngineStatusStore.lastVehicleState
        set(value) {
            EngineStatusStore.lastVehicleState = value
        }

    var isFirstMessage: Boolean
        get() = EngineStatusStore.isFirstMessage
        set(value) {
            EngineStatusStore.isFirstMessage = value
        }

    var nativeTurnsSinceReset: Int
        get() = EngineStatusStore.nativeTurnsSinceReset
        set(value) {
            EngineStatusStore.nativeTurnsSinceReset = value
        }

    var lastAiResponse: String
        get() = EngineStatusStore.lastAiResponse
        set(value) {
            EngineStatusStore.lastAiResponse = value
        }

    var lastInjectedTools: String
        get() = EngineStatusStore.lastInjectedTools
        set(value) {
            EngineStatusStore.lastInjectedTools = value
        }

    var isPrewarmed: Boolean
        get() = EngineStatusStore.isPrewarmed
        set(value) {
            EngineStatusStore.isPrewarmed = value
        }

    fun isReady(): Boolean =
        LiteRtEngineHost.isPresent() &&
            LlmConversationSession.conversation != null &&
            !EngineStatusStore.isInitializing

    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    /**
     * Marks an inference as entering the native engine and returns the conversation to use, or
     * `null` when the engine is not usable. Callers must pair this with [endInference].
     */
    fun beginInference(): Conversation? = LlmInferenceGate.begin {
        val active = LlmConversationSession.conversation
        if (active == null || !LiteRtEngineHost.isPresent() || EngineStatusStore.isInitializing) {
            null
        } else {
            active
        }
    }

    fun endInference() = LlmInferenceGate.end()

    /** True while at least one inference is inside the native engine. */
    fun hasActiveInference(): Boolean = LlmInferenceGate.hasActive()

    /**
     * Waits until no inference is inside the native engine, or [timeoutMs] elapses.
     * @return true when the engine is idle and safe to close/re-init.
     */
    suspend fun awaitInferenceDrain(
        timeoutMs: Long = AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS,
    ): Boolean = LlmInferenceGate.awaitDrain(timeoutMs)

    suspend fun autoInitialize(
        context: Context,
        force: Boolean = false,
        backendChoice: String = AssistantConfig.Backend.AUTO,
        callback: InitCallback? = null
    ) {
        if (!force && LiteRtEngineHost.isPresent()) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            val modelFile = LlmModelLocator.resolveSelectedModel(context)
            val targetBackend = LlmModelLocator.resolveBackendChoice(context, backendChoice)
            context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AssistantConfig.Prefs.SELECTED_MODEL, modelFile.absolutePath)
                .apply()

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

    /**
     * Brings up the LiteRT engine, walking the device backend fallback chain so a GPU request on
     * hardware without a working OpenCL driver degrades to CPU instead of leaving the assistant
     * permanently unusable.
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
            if (!force && LiteRtEngineHost.isPresent() && EngineStatusStore.currentModelPath == modelPath) {
                withContext(Dispatchers.Main) { callback?.onSuccess() }
                return
            }

            try {
                EngineStatusStore.isInitializing = true

                if (LiteRtEngineHost.isPresent() && !awaitInferenceDrain()) {
                    if (force) {
                        Log.w(
                            TAG,
                            "Force re-init requested while inference active; forcibly resetting " +
                                "activeInferences to prevent deadlock."
                        )
                        LlmInferenceGate.forceReset()
                    } else {
                        val busy = Exception("Model is still generating — refusing forced re-init")
                        Log.e(TAG, busy.message!!)
                        withContext(Dispatchers.Main) { callback?.onError(busy) }
                        return
                    }
                }

                val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val requestedBackend = LlmModelLocator.resolveBackendChoice(context, backendChoice)

                // maxNumTokens is pinned: larger windows overflow the KV cache on this hardware.
                val maxTokens = AssistantConfig.Llm.MAX_NUM_TOKENS
                if (prefs.getInt(AssistantConfig.Prefs.MAX_TOKENS, maxTokens) != maxTokens) {
                    prefs.edit().putInt(AssistantConfig.Prefs.MAX_TOKENS, maxTokens).apply()
                }

                LiteRtEngineHost.closeAllLocked()

                val initializedBackend = LiteRtEngineHost.startWithFallback(
                    context = context,
                    modelPath = modelPath,
                    requestedBackend = requestedBackend,
                    maxTokens = maxTokens,
                )

                EngineStatusStore.activeBackendString = initializedBackend
                EngineStatusStore.didFallBackFromRequestedBackend =
                    requestedBackend != AssistantConfig.Backend.AUTO &&
                        requestedBackend != initializedBackend

                resetConversation(context)
                EngineStatusStore.currentModelPath = modelPath
                EngineStatusStore.isPrewarmed = false

                Log.i(
                    TAG,
                    "Engine ready: model=$modelPath backend=$initializedBackend " +
                        "requested=$requestedBackend speculative=${EngineStatusStore.speculativeDecodingActive} " +
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
                EngineStatusStore.isInitializing = false
            }
        }
    }

    suspend fun getSystemPrompt(context: Context, query: String = ""): String =
        SystemPromptBuilder.build(context, query)

    fun capabilityReminder(): String = SystemPromptBuilder.capabilityReminder()

    suspend fun getDefaultSystemPrompt(context: Context, query: String = ""): String =
        SystemPromptBuilder.buildDefault(context, query)

    /**
     * Recycles the LiteRT conversation to bound KV-cache growth.
     *
     * @return false when an inference is still in flight — the caller should wait and retry rather
     * than force-closing the conversation under a live stream.
     */
    fun resetConversation(context: Context? = null, userQuery: String = ""): Boolean =
        LlmConversationSession.reset(userQuery)

    /**
     * Runs LiteRT's official benchmark API (prefill/decode tok/s + TTFT). Blocks for tens of
     * seconds — call off the UI thread. Does not disturb the live engine.
     */
    suspend fun runOfficialBenchmark(
        context: Context,
        prefillTokens: Int = AssistantConfig.Llm.BENCHMARK_PREFILL_TOKENS,
        decodeTokens: Int = AssistantConfig.Llm.BENCHMARK_DECODE_TOKENS,
    ): String = LlmBenchmarkRunner.run(
        context = context,
        modelPath = EngineStatusStore.currentModelPath,
        backendName = EngineStatusStore.activeBackendString,
        prefillTokens = prefillTokens,
        decodeTokens = decodeTokens,
    )

    /**
     * Releases the native engine to reclaim RAM.
     *
     * Refuses to close while an inference is still inside the engine — memory-pressure callbacks
     * and session teardown could otherwise free native state that a streaming callback was about
     * to touch, crashing the process.
     */
    fun unload(): Boolean {
        return LlmInferenceGate.withLock {
            val active = LlmInferenceGate.activeCount()
            if (active > 0) {
                Log.i(TAG, "Skipping unload: $active inference(s) still in flight.")
                return@withLock false
            }
            LiteRtEngineHost.unloadUnderLock()
            EngineStatusStore.markUnloaded()
            Log.i(TAG, "LLM model unloaded from memory to save resources.")
            true
        }
    }
}

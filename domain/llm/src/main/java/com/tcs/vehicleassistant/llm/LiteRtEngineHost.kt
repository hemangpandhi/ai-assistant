package com.tcs.vehicleassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.DeviceCapabilities
import com.tcs.vehicleassistant.core.KernelCacheManager

/**
 * Single responsibility: create, initialize, and close the LiteRT [Engine].
 *
 * Backend fallback and speculative-decoding flags stay here so conversation / prompt code does not
 * touch native engine construction.
 */
object LiteRtEngineHost {
    private const val TAG = "LiteRtEngineHost"

    @Volatile
    var engine: Engine? = null
        private set

    fun isPresent(): Boolean = engine != null

    /** Configures and initializes a LiteRT engine on a single [backendName]. Throws on failure. */
    @OptIn(ExperimentalApi::class)
    fun start(context: Context, modelPath: String, backendName: String, maxTokens: Int) {
        val backend = when (backendName) {
            AssistantConfig.Backend.NPU -> Backend.NPU()
            AssistantConfig.Backend.CPU -> Backend.CPU(threadCount = DeviceCapabilities.cpuCoreCount())
            else -> Backend.GPU()
        }

        // Persistent cache dir so compiled OpenCL kernels survive reboots and storage cleanups;
        // Context.cacheDir is evictable, which forced a full kernel recompile on every cold start.
        val cacheDir = KernelCacheManager.prepare(context, modelPath, backendName)

        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        // Force speculative decoding ON for maximum generation speed on generic GPUs
        val wantSpeculative = true
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
        EngineStatusStore.speculativeDecodingActive = false

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = maxTokens,
            cacheDir = cacheDir
        )

        val created = Engine(engineConfig)
        try {
            created.initialize()
            EngineStatusStore.speculativeDecodingActive = enableSpeculative
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

    /**
     * Walks [DeviceCapabilities.backendFallbackChain] so a GPU request on hardware without a
     * working OpenCL driver degrades to CPU instead of leaving the assistant permanently unusable.
     *
     * @return the backend name that successfully initialized
     */
    @OptIn(ExperimentalApi::class)
    fun startWithFallback(
        context: Context,
        modelPath: String,
        requestedBackend: String,
        maxTokens: Int,
    ): String {
        Log.i(TAG, "Accelerator probe: ${DeviceCapabilities.describe(context)}")
        val chain = DeviceCapabilities.backendFallbackChain(requestedBackend)
        Log.i(TAG, "Backend chain for request '$requestedBackend': $chain")

        var lastError: Exception? = null
        for (candidate in chain) {
            try {
                start(context, modelPath, candidate, maxTokens)
                return candidate
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "Backend '$candidate' failed to initialize; trying next in $chain", e)
                closeAllLocked()
                // Kernels serialized by a half-initialized GPU context are not reusable.
                KernelCacheManager.invalidate(context)
            }
        }
        throw lastError ?: Exception("No usable LiteRT backend on this device")
    }

    /**
     * Closes conversation + engine atomically under the inference gate.
     * Caller must hold the init mutex and have drained inferences.
     */
    fun closeAllLocked() {
        LlmInferenceGate.withLock {
            val active = LlmInferenceGate.activeCount()
            if (active > 0) {
                throw IllegalStateException(
                    "Refusing to close LiteRT engine with $active active inference(s)"
                )
            }
            LlmConversationSession.detachUnderLock()
            try {
                engine?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close engine", e)
            }
            engine = null
            LlmInferenceGate.forceReset()
        }
    }

    /** Closes native resources while already holding [LlmInferenceGate] (used by unload). */
    fun unloadUnderLock() {
        LlmConversationSession.detachUnderLock()
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanly close engine during unload.", e)
        } finally {
            engine = null
            LlmInferenceGate.forceReset()
        }
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
}

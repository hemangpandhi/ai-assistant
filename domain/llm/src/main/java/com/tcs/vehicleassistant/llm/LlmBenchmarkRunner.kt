package com.tcs.vehicleassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.DeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single responsibility: run LiteRT's official [benchmark] API without disturbing the live engine.
 */
object LlmBenchmarkRunner {
    private const val TAG = "LlmBenchmarkRunner"

    /**
     * Runs LiteRT's official benchmark (prefill/decode tok/s + TTFT). Blocks for tens of
     * seconds — call off the UI thread. Does not disturb the live engine.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun run(
        context: Context,
        modelPath: String,
        backendName: String,
        prefillTokens: Int = AssistantConfig.Llm.BENCHMARK_PREFILL_TOKENS,
        decodeTokens: Int = AssistantConfig.Llm.BENCHMARK_DECODE_TOKENS,
    ): String = withContext(Dispatchers.IO) {
        val resolvedPath = modelPath.ifBlank {
            context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AssistantConfig.Prefs.SELECTED_MODEL, null)
                .orEmpty()
        }
        if (resolvedPath.isBlank() || !File(resolvedPath).canRead()) {
            return@withContext "Benchmark skipped: no readable model path"
        }
        val resolvedBackend = backendName.takeIf { it != "Unknown" }
            ?: AssistantConfig.Backend.GPU
        val backend = when (resolvedBackend) {
            AssistantConfig.Backend.NPU -> Backend.NPU()
            AssistantConfig.Backend.CPU -> Backend.CPU(threadCount = DeviceCapabilities.cpuCoreCount())
            else -> Backend.GPU()
        }
        val cacheDir = File(context.cacheDir, "benchmark_${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            val info = benchmark(
                modelPath = resolvedPath,
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
            Log.i(TAG, "LiteRT benchmark ($resolvedBackend): $summary")
            summary
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT benchmark failed", e)
            "Benchmark failed: ${e.message}"
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}

package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.LLMManager
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags
import com.tcs.vehicleassistant.llm.ILLMProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Queues voice finals while the edge model is cold / prewarming (UI/UX extension).
 * Extracted from AgentOrchestrator so the shared orchestrator shell stays thinner.
 */
class LlmQueryReadinessGate(
    private val context: Context,
    private val featureFlags: AssistantFeatureFlags,
) {
    private var pendingQuery: Pair<String, Int>? = null
    private var waitJob: Job? = null

    /**
     * @return true if the query was deferred (caller should return); false if ready to run now.
     */
    fun deferIfNeeded(
        query: String,
        retryCount: Int,
        scope: CoroutineScope,
        onWaiting: (showToast: Boolean) -> Unit,
        onReady: (query: String, retryCount: Int) -> Unit,
        onFailure: (message: String) -> Unit,
    ): Boolean {
        if (LLMManager.isPrewarming ||
            (!featureFlags.isCloudActive && !LLMManager.isReady())
        ) {
            pendingQuery = query to retryCount
            onWaiting(retryCount == 0)
            if (waitJob?.isActive != true) {
                waitJob = scope.launch {
                    try {
                        if (!featureFlags.isCloudActive && !LLMManager.isReady()) {
                            val edgeProvider: ILLMProvider by getKoin().inject(named("edge"))
                            edgeProvider.initialize(context, force = false)
                        }
                        var waits = 0
                        while (
                            waits < 40 &&
                            (LLMManager.isPrewarming ||
                                (!featureFlags.isCloudActive && !LLMManager.isReady()))
                        ) {
                            delay(250)
                            waits++
                        }
                        val queued = pendingQuery
                        pendingQuery = null
                        if (LLMManager.isPrewarming ||
                            (!featureFlags.isCloudActive && !LLMManager.isReady())
                        ) {
                            onFailure("Model not ready yet. Try again in a moment.")
                            return@launch
                        }
                        if (queued != null) {
                            onReady(queued.first, queued.second)
                        }
                    } catch (_: Exception) {
                        pendingQuery = null
                        onFailure("Model not loaded. Open the app to load a model.")
                    }
                }
            }
            return true
        }
        return false
    }

    fun cancel() {
        waitJob?.cancel()
        waitJob = null
        pendingQuery = null
    }
}

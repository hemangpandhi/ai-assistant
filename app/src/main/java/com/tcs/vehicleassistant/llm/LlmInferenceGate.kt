package com.tcs.vehicleassistant.llm

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.tcs.vehicleassistant.core.AssistantConfig
import kotlinx.coroutines.delay

/**
 * Single responsibility: serialize native inference vs engine teardown.
 *
 * Callers claim the engine with [begin] / [end] so [unload] and re-init cannot close LiteRT
 * under an in-flight `sendMessageAsync` callback.
 */
object LlmInferenceGate {
    private const val TAG = "LlmInferenceGate"

    private val lock = Any()
    private var activeInferences = 0

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /**
     * Marks an inference as entering the native engine and returns the conversation from
     * [resolveConversation], or `null` when the engine is not usable. [resolveConversation] runs
     * under the gate lock so readiness cannot race teardown.
     */
    fun begin(resolveConversation: () -> Conversation?): Conversation? = synchronized(lock) {
        val conversation = resolveConversation() ?: return@synchronized null
        activeInferences++
        conversation
    }

    fun end() = synchronized(lock) {
        if (activeInferences > 0) activeInferences--
    }

    fun hasActive(): Boolean = synchronized(lock) { activeInferences > 0 }

    fun activeCount(): Int = synchronized(lock) { activeInferences }

    /**
     * Clears the counter. Safe to call while already holding [withLock] (reentrant monitor)
     * or from a forced re-init path that must proceed despite a stuck claim.
     */
    fun forceReset() = synchronized(lock) { activeInferences = 0 }

    /**
     * Waits until no inference is inside the native engine, or [timeoutMs] elapses.
     * @return true when the engine is idle and safe to close/re-init.
     */
    suspend fun awaitDrain(
        timeoutMs: Long = AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (hasActive()) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "Inference drain timed out with activeInferences still > 0")
                return false
            }
            delay(50)
        }
        return true
    }
}

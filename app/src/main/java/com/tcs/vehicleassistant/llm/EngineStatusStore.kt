package com.tcs.vehicleassistant.llm

/**
 * Single responsibility: mutable LiteRT readiness / session metadata shared by UI and orchestrator.
 *
 * Keeps [com.tcs.vehicleassistant.LLMManager] from owning both lifecycle logic and status fields.
 */
object EngineStatusStore {
    @Volatile
    var currentModelPath: String = ""

    @Volatile
    var isInitializing: Boolean = false

    @Volatile
    var activeBackendString: String = "Unknown"

    /** True when the active backend differs from what the user asked for, after a fallback. */
    @Volatile
    var didFallBackFromRequestedBackend: Boolean = false

    /** True when the last successful engine start enabled speculative decoding / MTP. */
    @Volatile
    var speculativeDecodingActive: Boolean = false

    @Volatile
    var isPrewarmed: Boolean = false

    @Volatile
    var lastVehicleState: String = ""

    @Volatile
    var isFirstMessage: Boolean = true

    @Volatile
    var nativeTurnsSinceReset: Int = 0

    @Volatile
    var lastAiResponse: String = ""

    @Volatile
    var lastInjectedTools: String = ""

    /** Soft reset markers when no engine is loaded (does not clear lastAiResponse). */
    fun markIdleConversationCounters() {
        isFirstMessage = true
        nativeTurnsSinceReset = 0
    }

    fun markUnloaded() {
        isFirstMessage = true
        nativeTurnsSinceReset = 0
        isPrewarmed = false
        lastAiResponse = ""
        speculativeDecodingActive = false
    }
}

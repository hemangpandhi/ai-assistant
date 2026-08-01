package com.tcs.vehicleassistant.core

/**
 * Pure backend-selection policy, kept free of Android APIs so it can be asserted directly in JVM
 * unit tests. [DeviceCapabilities] supplies the hardware facts; this object decides what to do
 * with them.
 */
object GpuBackendResolver {

    /**
     * Backend LiteRT should be configured with for a [requested] preference.
     *
     * - `"Auto"` picks GPU when an OpenCL driver exists, otherwise CPU.
     * - An explicit `"GPU"` request is downgraded to CPU when no OpenCL driver exists, because
     *   `Backend.GPU()` cannot initialize without one.
     * - `"NPU"` and `"CPU"` are honoured as-is; unrecognised values behave like `"Auto"`.
     */
    fun resolve(requested: String, openClAvailable: Boolean): String = when (requested) {
        AssistantConfig.Backend.CPU -> AssistantConfig.Backend.CPU
        AssistantConfig.Backend.NPU -> AssistantConfig.Backend.NPU
        AssistantConfig.Backend.GPU ->
            if (openClAvailable) AssistantConfig.Backend.GPU else AssistantConfig.Backend.CPU
        else ->
            if (openClAvailable) AssistantConfig.Backend.GPU else AssistantConfig.Backend.CPU
    }

    /**
     * Ordered backends to attempt for [requested]. The first entry is [resolve]'s answer; CPU is
     * always the final entry so initialization has a guaranteed terminal fallback.
     */
    fun fallbackChain(requested: String, openClAvailable: Boolean): List<String> {
        val primary = resolve(requested, openClAvailable)
        val chain = mutableListOf(primary)
        if (primary == AssistantConfig.Backend.NPU && openClAvailable) {
            chain.add(AssistantConfig.Backend.GPU)
        }
        if (!chain.contains(AssistantConfig.Backend.CPU)) {
            chain.add(AssistantConfig.Backend.CPU)
        }
        return chain
    }
}

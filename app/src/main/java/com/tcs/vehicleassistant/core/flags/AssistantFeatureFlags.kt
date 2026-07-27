package com.tcs.vehicleassistant.core.flags

import android.content.Context

enum class InferenceRoutingPolicy {
    PreferEdge,
    ForceEdge,
    ForceCloud,
}

/**
 * Central feature / routing flags (JetPacker :core:flags analogue).
 * Replaces LocalLLMActivity companion statics for agent routing.
 */
class AssistantFeatureFlags(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var speculativeDecodingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPECULATIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_SPECULATIVE, value).apply()

    var agenticLoopEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENTIC_LOOP, true)
        set(value) = prefs.edit().putBoolean(KEY_AGENTIC_LOOP, value).apply()

    var semanticToolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEMANTIC_TOOLS, false)
        set(value) = prefs.edit().putBoolean(KEY_SEMANTIC_TOOLS, value).apply()

    var routingPolicy: InferenceRoutingPolicy
        get() = when {
            prefs.getBoolean(KEY_CLOUD_ACTIVE, false) -> InferenceRoutingPolicy.ForceCloud
            else -> InferenceRoutingPolicy.PreferEdge
        }
        set(value) {
            when (value) {
                InferenceRoutingPolicy.ForceCloud ->
                    prefs.edit().putBoolean(KEY_CLOUD_ACTIVE, true).apply()
                InferenceRoutingPolicy.PreferEdge,
                InferenceRoutingPolicy.ForceEdge ->
                    prefs.edit().putBoolean(KEY_CLOUD_ACTIVE, false).apply()
            }
        }

    var cloudModelName: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value).apply()

    val isCloudActive: Boolean
        get() = routingPolicy == InferenceRoutingPolicy.ForceCloud

    fun setCloudMode(active: Boolean, modelName: String) {
        prefs.edit()
            .putBoolean(KEY_CLOUD_ACTIVE, active)
            .putString(KEY_CLOUD_MODEL, modelName)
            .apply()
        // Keep legacy companions in sync during migration.
        com.tcs.vehicleassistant.LocalLLMActivity.isCloudModelActive = active
        com.tcs.vehicleassistant.LocalLLMActivity.currentCloudModelName = modelName
    }

    fun syncFromLegacyCompanions() {
        val active = com.tcs.vehicleassistant.LocalLLMActivity.isCloudModelActive
        val name = com.tcs.vehicleassistant.LocalLLMActivity.currentCloudModelName
        prefs.edit()
            .putBoolean(KEY_CLOUD_ACTIVE, active)
            .putString(KEY_CLOUD_MODEL, name)
            .apply()
    }

    companion object {
        private const val PREFS = "app_prefs"
        private const val KEY_CLOUD_ACTIVE = "cloud_model_active"
        private const val KEY_CLOUD_MODEL = "cloud_model_name"
        private const val KEY_SPECULATIVE = "speculative_decoding"
        private const val KEY_AGENTIC_LOOP = "agentic_loop_enabled"
        private const val KEY_SEMANTIC_TOOLS = "semantic_tools_enabled"
    }
}

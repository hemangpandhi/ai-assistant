package com.tcs.vehicleassistant.assistant

import android.content.Context
import com.assistant.ui.assistant.api.AssistantRuntime
import com.assistant.ui.assistant.api.NoOpAssistantHost
import com.assistant.ui.assistant.face.AssistantFaceConfig
import com.assistant.ui.assistant.face.AssistantFaceKind
import com.assistant.ui.assistant.ui.immersive.AssistantPlacementConfig

/**
 * Installs Compose immersive UI runtime with Hybrid face default.
 * Does not touch agent / LLM paths — presentation wiring only.
 */
object AssistantUiBootstrap {
    @Volatile
    private var installed = false

    val backend: StableSessionAssistantBackend
        get() = requireNotNull(AssistantRuntime.backend as? StableSessionAssistantBackend) {
            "AssistantUiBootstrap.install() was not called"
        }

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            AssistantFaceConfig.install(app)
            AssistantPlacementConfig.install(app)
            ensureHybridFace(app)

            val uiBackend = StableSessionAssistantBackend()
            AssistantRuntime.install(host = NoOpAssistantHost, backend = uiBackend)
            installed = true
        }
    }

    private fun ensureHybridFace(context: Context) {
        val prefs = context.getSharedPreferences(AssistantFaceConfig.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("kind", null).isNullOrBlank()) {
            AssistantFaceConfig.set(context, AssistantFaceKind.ImmersiveHybrid)
        }
    }
}

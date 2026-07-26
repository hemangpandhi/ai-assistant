package com.tcs.vehicleassistant.assistant

import android.app.Application
import com.test.design.assistant.api.AssistantRuntime
import com.test.design.presentation.assistant.AssistantFaceConfig
import com.test.design.presentation.assistant.AssistantFaceKind
import com.test.design.presentation.assistant.backend.DemoAssistantBackend
import com.test.design.presentation.assistant.backend.platformAssistantTts

/**
 * Installs Compose assistant runtime + UI profile for the host app.
 *
 * Default UI: Compose immersive with immersive-hybrid face.
 * Swap [DemoAssistantBackend] for [VehicleAgentAssistantBackend] when ready.
 */
object AssistantRuntimeBootstrap {
    fun install(app: Application, useDemoBackend: Boolean = true) {
        AssistantUiProfile.install(app)
        AssistantFaceConfig.install(app)
        ensureDefaultFace(app)

        val host = VehicleAssistantHost(app)
        val backend = if (useDemoBackend) {
            DemoAssistantBackend(speakingTts = platformAssistantTts(app))
        } else {
            VehicleAgentAssistantBackend()
        }
        AssistantRuntime.install(host = host, backend = backend)
    }

    private fun ensureDefaultFace(app: Application) {
        val prefs = app.getSharedPreferences(AssistantFaceConfig.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getString("kind", null).isNullOrBlank()) {
            AssistantFaceConfig.set(app, AssistantFaceKind.ImmersiveHybrid)
        }
    }
}

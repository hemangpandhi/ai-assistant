package com.tcs.vehicleassistant.assistant

import android.app.Application
import com.assistant.ui.assistant.api.AssistantDebugStripConfig
import com.assistant.ui.assistant.api.AssistantRuntime
import com.assistant.ui.assistant.face.AssistantFaceConfig
import com.assistant.ui.assistant.face.AssistantFaceKind
import com.assistant.ui.assistant.backend.DemoAssistantBackend
import com.assistant.ui.assistant.backend.SilentAssistantTts

/**
 * Installs Compose assistant runtime + UI profile for the host app.
 *
 * Default: production [VehicleAgentAssistantBackend] (mic / hotword / LLM / TTS via master agent).
 */
object AssistantRuntimeBootstrap {
    fun install(app: Application, useDemoBackend: Boolean = false) {
        AssistantUiProfile.install(app)
        AssistantFaceConfig.install(app)
        AssistantDebugStripConfig.install(app)
        ensureDefaultFace(app)

        val host = VehicleAssistantHost(app)
        val backend = if (useDemoBackend) {
            DemoAssistantBackend(
                speakingTts = SilentAssistantTts,
            )
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

package com.tcs.vehicleassistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.tcs.vehicleassistant.assistant.AssistantUiProfile
import com.tcs.vehicleassistant.assistant.session.ComposeAssistantSession

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        // ui_ux extension seam
        AssistantUiProfile.install(this)
        return if (AssistantUiProfile.isCompose()) {
            ComposeAssistantSession(this)
        } else {
            AssistantSession(this)
        }
    }
}

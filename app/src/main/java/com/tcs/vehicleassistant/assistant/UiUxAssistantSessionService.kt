package com.tcs.vehicleassistant.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.tcs.vehicleassistant.AssistantSession
import com.tcs.vehicleassistant.assistant.session.ComposeAssistantSession

/**
 * UI/UX session service — keeps refactor [com.tcs.vehicleassistant.AssistantSessionService]
 * byte-identical. Point `voice_interaction_service.xml` sessionService here.
 */
class UiUxAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        AssistantUiProfile.install(this)
        return if (AssistantUiProfile.isCompose()) {
            ComposeAssistantSession(this)
        } else {
            AssistantSession(this)
        }
    }
}

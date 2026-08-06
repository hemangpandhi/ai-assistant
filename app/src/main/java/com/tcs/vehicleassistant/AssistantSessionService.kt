package com.tcs.vehicleassistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onCreate() {
        super.onCreate()
        com.tcs.vehicleassistant.assistant.AssistantUiBootstrap.install(applicationContext)
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantSession(this)
    }
}

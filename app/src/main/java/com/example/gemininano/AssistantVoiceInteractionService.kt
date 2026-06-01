package com.example.gemininano

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class AssistantVoiceInteractionService : VoiceInteractionService() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.gemininano.WAKE_WORD_DETECTED") {
                showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        VehicleManager.initialize(this) // 'this' is a Service, which is a valid Context for Car Service binding!
        val filter = IntentFilter("com.example.gemininano.WAKE_WORD_DETECTED")
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onShutdown() {
        super.onShutdown()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }
}

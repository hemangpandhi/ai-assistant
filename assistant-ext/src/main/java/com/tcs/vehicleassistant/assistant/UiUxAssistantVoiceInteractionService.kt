package com.tcs.vehicleassistant.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.tcs.vehicleassistant.LocalLLMActivity
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.WakeWordService

/**
 * Primary UI [VoiceInteractionService] — keeps refactor
 * [com.tcs.vehicleassistant.AssistantVoiceInteractionService] byte-identical.
 *
 * Always starts master [WakeWordService] (no parallel TTFR wake path).
 */
class UiUxAssistantVoiceInteractionService : VoiceInteractionService() {

    companion object {
        @Volatile
        var instance: UiUxAssistantVoiceInteractionService? = null
            private set

        fun triggerSession(context: Context? = null) {
            android.util.Log.d("WakeWord", "UiUx triggerSession called. instance is $instance")
            val live = instance
            if (live != null) {
                live.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
            } else if (context != null) {
                android.util.Log.w("WakeWord", "UiUx VoiceInteractionService unbound! Launching fallback Activity.")
                val intent = Intent(context, LocalLLMActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                intent.putExtra("auto_trigger_mic", true)
                context.startActivity(intent)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.tcs.vehicleassistant.WAKE_WORD_DETECTED") {
                triggerSession(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AssistantUiProfile.install(this)
        val filter = IntentFilter("com.tcs.vehicleassistant.WAKE_WORD_DETECTED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onReady() {
        super.onReady()
        VehicleManager.initialize(this)
        try {
            AssistantUiProfile.install(this)
            startService(Intent(this, WakeWordService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        if (instance === this) instance = null
    }
}

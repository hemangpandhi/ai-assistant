package com.tcs.vehicleassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class AssistantVoiceInteractionService : VoiceInteractionService() {

    companion object {
        var instance: AssistantVoiceInteractionService? = null
        
        fun triggerSession(context: Context? = null) {
            android.util.Log.d("WakeWord", "triggerSession called. instance is $instance")
            if (instance != null) {
                val args = Bundle()
                args.putBoolean("auto_trigger_mic", true)
                instance?.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
            } else if (context != null) {
                android.util.Log.w("WakeWord", "VoiceInteractionService unbound! Launching fallback Activity.")
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
        val filter = IntentFilter("com.tcs.vehicleassistant.WAKE_WORD_DETECTED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onReady() {
        super.onReady()
        VehicleManager.initialize(this) // 'this' is a Service, which is a valid Context for Car Service binding!
        try {
            startService(Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {}
    }

    override fun onShutdown() {
        super.onShutdown()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }
}

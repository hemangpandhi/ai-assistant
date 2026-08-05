package com.tcs.vehicleassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.tcs.vehicleassistant.core.AssistantConfig

class AssistantVoiceInteractionService : VoiceInteractionService() {

    companion object {
        var instance: AssistantVoiceInteractionService? = null
        
        fun triggerSession(context: Context? = null) {
            android.util.Log.d("WakeWord", "triggerSession called. instance is $instance")
            if (instance != null) {
                instance?.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
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
            if (intent.action == AssistantConfig.WakeWordAction.DETECTED_BROADCAST) {
                triggerSession(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Only this app broadcasts the wake-word event, across its own process boundary, so the
        // receiver must stay unexported: an exported one would let any app open the assistant.
        registerReceiver(
            receiver,
            IntentFilter(AssistantConfig.WakeWordAction.DETECTED_BROADCAST),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onReady() {
        super.onReady()
        VehicleManager.initialize(this) // 'this' is a Service, which is a valid Context for Car Service binding!

        // Starting the microphone service unconditionally here re-enabled wake-word listening on
        // every boot even after the user turned it off in settings.
        if (AssistantConfig.isWakeWordDisabledForTest(this)) {
            android.util.Log.i("WakeWord", "WAKE_WORD_DISABLED_FOR_TEST — not starting Vosk.")
            return
        }
        val enabled = getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AssistantConfig.Prefs.WAKE_WORD_ENABLED, false)
        if (!enabled) {
            android.util.Log.i("WakeWord", "Wake word disabled in settings; not starting the listener.")
            return
        }
        try {
            startService(Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {
            android.util.Log.w("WakeWord", "Could not start the wake word service", e)
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Never registered; nothing to detach.
        }
        // Clearing the static handle keeps a destroyed service from leaking and stops
        // triggerSession() from calling showSession() on a dead instance.
        if (instance === this) instance = null
    }
}

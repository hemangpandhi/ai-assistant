package com.tcs.vehicleassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.assistant.ui.assistant.ui.immersive.AssistantComposePrewarmer
import kotlin.concurrent.thread

class AssistantVoiceInteractionService : VoiceInteractionService() {

    companion object {
        var instance: AssistantVoiceInteractionService? = null
        
        fun triggerSession(context: Context? = null, fromHotword: Boolean = false) {
            android.util.Log.d("WakeWord", "triggerSession called. instance is $instance hotword=$fromHotword")
            val ctx = context ?: instance
            // Pre-arm command STT before the overlay appears (icon path; hotword already arms).
            if (ctx != null && !fromHotword) {
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.preArm(ctx, reason = "assist-icon")
            } else if (ctx != null && fromHotword) {
                // Hotword path usually pre-armed in WakeWordService; ensure if race lost.
                com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.preArm(ctx, reason = "hotword-session")
            }
            if (instance != null) {
                val args = Bundle().apply {
                    putString(
                        com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin.BUNDLE_KEY,
                        if (fromHotword) {
                            com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin.TOKEN_HOTWORD
                        } else {
                            com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin.TOKEN_ICON
                        },
                    )
                }
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
                triggerSession(context, fromHotword = true)
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
        // Start wake-word as a foreground service so hotword stays alive.
        // Car init is off the main thread; Compose is prewarmed so the first
        // session inflate does not pay cold class-load / runtime cost.
        android.os.Handler(mainLooper).post {
            AssistantComposePrewarmer.warm(this)
            try {
                val wake = Intent(this, WakeWordService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(wake)
                } else {
                    startService(wake)
                }
            } catch (e: Exception) {
                android.util.Log.e("WakeWord", "Failed to start WakeWordService", e)
            }
            thread(name = "vis-car-init", isDaemon = true) {
                try {
                    VehicleManager.initialize(this@AssistantVoiceInteractionService)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }
}

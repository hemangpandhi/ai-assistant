package com.tcs.vehicleassistant.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin
import com.tcs.vehicleassistant.LocalLLMActivity
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.WakeWordService
import com.tcs.vehicleassistant.core.AssistantConfig

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
            triggerSessionWithQuery(null, context)
        }

        fun triggerSessionWithQuery(initialQuery: String?, context: Context? = null, directSpeech: String? = null) {
            android.util.Log.d("WakeWord", "UiUx triggerSessionWithQuery called. instance is $instance")
            val live = instance
            if (live != null) {
                val args = Bundle().apply {
                    putString(
                        ImmersiveSummonOrigin.BUNDLE_KEY,
                        ImmersiveSummonOrigin.TOKEN_HOTWORD,
                    )
                    if (initialQuery != null) {
                        putString("INITIAL_QUERY", initialQuery)
                    }
                    if (directSpeech != null) {
                        putString("DIRECT_SPEECH", directSpeech)
                    }
                }
                live.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
            } else if (context != null) {
                android.util.Log.w("WakeWord", "UiUx VoiceInteractionService unbound! Launching fallback Activity.")
                val intent = Intent(context, LocalLLMActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                intent.putExtra("auto_trigger_mic", initialQuery == null)
                if (initialQuery != null) {
                    intent.putExtra("INITIAL_QUERY", initialQuery)
                }
                context.startActivity(intent)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AssistantConfig.WakeWordAction.DETECTED_BROADCAST -> {
                    triggerSession(context)
                }
                AssistantConfig.WakeWordAction.PREWARM_BROADCAST -> {
                    instance?.showSession(
                        Bundle().apply { putBoolean("is_prewarm", true) },
                        0 // Do NOT pass SHOW_WITH_ASSIST to avoid triggering the system assist sound early
                    )
                }
                AssistantConfig.WakeWordAction.CANCEL_PREWARM_BROADCAST -> {
                    // Send an empty bundle with is_cancel_prewarm to instruct the session to hide itself.
                    // This avoids killing a real session if it happened to start in the meantime.
                    instance?.showSession(
                        Bundle().apply { putBoolean("is_cancel_prewarm", true) },
                        0
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AssistantUiProfile.install(this)
        val filter = IntentFilter().apply {
            addAction(AssistantConfig.WakeWordAction.DETECTED_BROADCAST)
            addAction(AssistantConfig.WakeWordAction.PREWARM_BROADCAST)
            addAction(AssistantConfig.WakeWordAction.CANCEL_PREWARM_BROADCAST)
        }
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
            if (AssistantConfig.isWakeWordDisabledForTest(this)) {
                android.util.Log.i(
                    "WakeWord",
                    "WAKE_WORD_DISABLED_FOR_TEST — not starting Vosk WakeWordService",
                )
                // Ensure any prior instance releases the mic for session STT.
                runCatching {
                    startService(
                        Intent(this, WakeWordService::class.java)
                            .setAction(AssistantConfig.WakeWordAction.STOP),
                    )
                }
            } else {
                startService(Intent(this, WakeWordService::class.java))
            }
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

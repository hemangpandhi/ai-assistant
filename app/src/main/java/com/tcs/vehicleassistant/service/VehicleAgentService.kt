package com.tcs.vehicleassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tcs.vehicleassistant.R
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.hardware.AndroidAudioManager
import com.tcs.vehicleassistant.hardware.IAudioManager

/**
 * Agent host: owns [AndroidAudioManager] (TTS + session [com.tcs.vehicleassistant.hardware.ear.AssistantEar])
 * and [AssistantViewModel].
 */
class VehicleAgentService : Service(), ComponentCallbacks2 {

    private val binder = LocalBinder()

    lateinit var audioManager: IAudioManager
        private set
    lateinit var viewModel: AssistantViewModel
        private set

    inner class LocalBinder : Binder() {
        fun getService(): VehicleAgentService = this@VehicleAgentService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val audio = AndroidAudioManager(this)
        audioManager = audio
        viewModel = AssistantViewModel(this, audio)

        audio.initialize(
            onSuccess = {
                audio.playSilentUtterance(10, "PREWARM")
                audio.prewarmEar()
            },
            onError = {
                // Ignore silent failure in background
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_RELOAD_TTS && ::audioManager.isInitialized) {
            audioManager.reloadTtsFromPrefs()
            android.util.Log.i(TAG, "Reloaded cabin TTS from prefs")
        } else if (
            intent?.action == "com.tcs.vehicleassistant.ACTION_GREET_USER" &&
            ::viewModel.isInitialized
        ) {
            val userName = intent.getStringExtra("USER_NAME") ?: "User"
            val text =
                "Welcome $userName, I am adjusting your vehicle controls based on your preference."
            viewModel.speakAndDismiss(text)
            com.tcs.vehicleassistant.assistant.UiUxAssistantVoiceInteractionService
                .triggerSessionWithQuery(null, this, directSpeech = text)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(getString(R.string.agent_notification_text))
            .setSmallIcon(R.drawable.ic_mic_small)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Agentic Service Channel",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        try {
            viewModel.destroy()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to destroy view model", e)
        }
        try {
            audioManager.shutdown()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to shut down audio manager", e)
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            val unloaded = com.tcs.vehicleassistant.LLMManager.unload()
            android.util.Log.w(TAG, "Memory pressure level=$level. LLM unloaded=$unloaded")
        }
    }

    companion object {
        const val TAG = "VehicleAgentService"
        const val CHANNEL_ID = "VehicleAgentServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_RELOAD_TTS = "com.tcs.vehicleassistant.ACTION_RELOAD_TTS"
    }
}

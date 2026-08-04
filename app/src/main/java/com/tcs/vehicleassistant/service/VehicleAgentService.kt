package com.tcs.vehicleassistant.service

import com.tcs.vehicleassistant.llm.LLMManager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tcs.vehicleassistant.R
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.hardware.AndroidAudioManager
import android.content.ComponentCallbacks2
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.UserManager

class VehicleAgentService : Service(), ComponentCallbacks2 {

    private val binder = LocalBinder()
    
    lateinit var audioManager: AndroidAudioManager
    lateinit var viewModel: AssistantViewModel

    inner class LocalBinder : Binder() {
        fun getService(): VehicleAgentService = this@VehicleAgentService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        audioManager = AndroidAudioManager(this)
        viewModel = AssistantViewModel(this, audioManager)
        
        audioManager.initialize(
            onSuccess = {
                audioManager.playSilentUtterance(10, "PREWARM")
            },
            onError = {
                // Ignore silent failure in background
            }
        )

    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d(TAG, "onStartCommand called with action: ${intent?.action}")
        if (intent?.action == ACTION_RELOAD_TTS && ::audioManager.isInitialized) {
            audioManager.reloadTtsFromPrefs()
            android.util.Log.i(TAG, "Reloaded cabin TTS from prefs")
        } else if (intent?.action == "com.tcs.vehicleassistant.ACTION_GREET_USER" && ::viewModel.isInitialized) {
            val userName = intent.getStringExtra("USER_NAME") ?: "User"
            val text = "Welcome $userName, I am adjusting your vehicle controls based on your preference."
            viewModel.speakAndDismiss(text)
            com.tcs.vehicleassistant.assistant.UiUxAssistantVoiceInteractionService.triggerSessionWithQuery(null, this, directSpeech = text)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(getString(R.string.agent_notification_text))
            .setSmallIcon(R.drawable.ic_mic_small)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        // Sticky so the OS restores the agent after a low-memory kill; the previous
        // START_NOT_STICKY left the assistant dead until the user manually reopened the app.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Agentic Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Full teardown. This previously only destroyed the speech recognizer, leaking the TTS engine,
     * the AudioTrack, and the ViewModel's coroutine scope on every service restart.
     */
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
            // unload() declines while an inference is still inside the native engine, so a
            // memory-pressure callback can no longer free state a streaming callback is using.
            val unloaded = com.tcs.vehicleassistant.llm.LLMManager.unload()
            android.util.Log.w(TAG, "Memory pressure level=$level. LLM unloaded=$unloaded")
        }
    }

    companion object {
        const val TAG = "VehicleAgentService"
        const val CHANNEL_ID = "AgenticServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_RELOAD_TTS = "com.tcs.vehicleassistant.RELOAD_TTS"
    }
}

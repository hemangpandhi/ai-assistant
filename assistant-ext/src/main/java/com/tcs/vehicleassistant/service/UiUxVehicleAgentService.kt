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
import com.tcs.vehicleassistant.controller.UiUxAssistantViewModel
import com.tcs.vehicleassistant.core.AgentRuntime
import com.tcs.vehicleassistant.hardware.SessionAndroidAudioManager
import com.tcs.vehicleassistant.llm.LlmEngine
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

/**
 * UI/UX service wrapper that leaves the refactor-owned [VehicleAgentService] untouched.
 */
class UiUxVehicleAgentService : Service(), ComponentCallbacks2 {
    private val binder = LocalBinder()

    lateinit var audioManager: SessionAndroidAudioManager
        private set
    lateinit var viewModel: UiUxAssistantViewModel
        private set
    private lateinit var llmEngine: LlmEngine

    inner class LocalBinder : Binder() {
        fun getService(): UiUxVehicleAgentService = this@UiUxVehicleAgentService
    }

    override fun onCreate() {
        super.onCreate()
        AgentRuntime.resetForService()
        createNotificationChannel()

        audioManager = getKoin().get()
        viewModel = getKoin().get()
        llmEngine = getKoin().get()

        audioManager.initialize(
            onSuccess = {
                audioManager.ensureWarmRecognizer()
                audioManager.playSilentUtterance(10, "PREWARM")
            },
            onError = {},
        )
        audioManager.ensureWarmRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vehicle AI Agent")
            .setContentText("Processing tasks in the background...")
            .setSmallIcon(R.drawable.ic_mic_small)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        AgentRuntime.shutdown()
        if (::audioManager.isInitialized) {
            audioManager.destroySpeechRecognizer()
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            AgentRuntime.scope.launch {
                runCatching { llmEngine.unload() }
            }
            AgentRuntime.cancelChildren()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agentic Service Channel",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "AgenticServiceChannel"
        const val NOTIFICATION_ID = 1
    }
}

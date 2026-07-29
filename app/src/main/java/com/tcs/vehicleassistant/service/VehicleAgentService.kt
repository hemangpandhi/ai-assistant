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
import com.tcs.vehicleassistant.assistant.AssistantUiProfile
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.UiUxAssistantViewModel
import com.tcs.vehicleassistant.core.AgentRuntime
import com.tcs.vehicleassistant.hardware.SessionAndroidAudioManager
import com.tcs.vehicleassistant.hardware.SessionAudioPort
import com.tcs.vehicleassistant.llm.LlmEngine
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

class VehicleAgentService : Service(), ComponentCallbacks2 {

    private val binder = LocalBinder()

    lateinit var audioManager: SessionAndroidAudioManager
    lateinit var viewModel: AssistantViewModel
    lateinit var uiUxViewModel: UiUxAssistantViewModel
    private lateinit var llmEngine: LlmEngine

    inner class LocalBinder : Binder() {
        fun getService(): VehicleAgentService = this@VehicleAgentService
    }

    override fun onCreate() {
        super.onCreate()
        AgentRuntime.resetForService()
        createNotificationChannel()

        audioManager = getKoin().get<SessionAudioPort>() as SessionAndroidAudioManager
        AssistantUiProfile.install(this)
        if (AssistantUiProfile.isCompose()) {
            uiUxViewModel = getKoin().get()
        } else {
            viewModel = AssistantViewModel(this, audioManager)
        }
        llmEngine = getKoin().get()

        audioManager.initialize(
            onSuccess = {
                audioManager.ensureWarmRecognizer()
                audioManager.playSilentUtterance(10, "PREWARM")
            },
            onError = {
                // Ignore silent failure in background
            }
        )
        // Eager warm even if TTS is slow — STT create is independent.
        audioManager.ensureWarmRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "AgenticServiceChannel")
            .setContentTitle("Vehicle AI Agent")
            .setContentText("Processing tasks in the background...")
            .setSmallIcon(R.drawable.ic_mic_small)
            .build()

        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "AgenticServiceChannel",
                "Agentic Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        AgentRuntime.shutdown()
        audioManager.destroySpeechRecognizer()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Unload sooner under pressure — do not force System.gc() (causes jank).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            android.util.Log.w(
                "VehicleAgentService",
                "OS memory pressure (level $level). Unloading LLM.",
            )
            AgentRuntime.scope.launch {
                runCatching { llmEngine.unload() }
                    .onFailure { com.tcs.vehicleassistant.LLMManager.unload() }
            }
            AgentRuntime.cancelChildren()
        }
    }
}

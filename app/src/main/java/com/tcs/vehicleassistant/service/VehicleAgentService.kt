package com.tcs.vehicleassistant.service

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
        super.onDestroy()
        audioManager.destroySpeechRecognizer()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            android.util.Log.w("VehicleAgentService", "OS Memory Pressure Critical (Level $level). Unloading LLM from RAM.")
            try {
                val edgeProvider = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.llm.ILLMProvider>(org.koin.core.qualifier.named("edge"))
                edgeProvider.unload()
            } catch (e: Exception) {
                // Koin or provider might not be initialized yet
            }
            System.gc()
        }
    }
}

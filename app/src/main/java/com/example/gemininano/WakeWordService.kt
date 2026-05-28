package com.example.gemininano

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class WakeWordService : Service(), RecognitionListener {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var wakeWord = "hey auto"

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("wakeword", "Wake Word Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, "wakeword")
            .setContentTitle("Wake Word Listening")
            .setContentText("Say your wake word to summon assistant")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        
        updateWakeWord()

        StorageService.unpack(this, "model", "model",
            { m: Model ->
                model = m
                recognizerSetup()
            },
            { exception: java.io.IOException -> 
                Log.e("WakeWord", "Failed to unpack model: ${exception.message}") 
            }
        )
    }

    private fun updateWakeWord() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        wakeWord = prefs.getString("wake_word", "hey auto")?.lowercase() ?: "hey auto"
    }

    private fun recognizerSetup() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e("WakeWord", "Failed to init recognizer: ${e.message}")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateWakeWord()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.apply {
            stop()
            shutdown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onPartialResult(hypothesis: String) {
        checkWakeWord(hypothesis)
    }

    override fun onResult(hypothesis: String) {
        checkWakeWord(hypothesis)
    }

    override fun onFinalResult(hypothesis: String) {
        checkWakeWord(hypothesis)
    }
    
    private fun checkWakeWord(hypothesis: String) {
        if (hypothesis.lowercase().contains(wakeWord)) {
            Log.d("WakeWord", "Wake word detected: $wakeWord")
            sendBroadcast(Intent("com.example.gemininano.WAKE_WORD_DETECTED"))
            
            // Briefly pause to prevent self-trigger loop
            speechService?.stop()
            CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                speechService?.startListening(this@WakeWordService)
            }
        }
    }

    override fun onError(exception: Exception) {
        Log.e("WakeWord", "Vosk Error: ${exception.message}")
    }
    
    override fun onTimeout() {}
}

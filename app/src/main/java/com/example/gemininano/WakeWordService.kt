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

class WakeWordService : Service() {

    companion object {
        var sharedModel: Model? = null
    }

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

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val destDir = java.io.File(filesDir, "model")
                if (!destDir.exists() || destDir.listFiles()?.isEmpty() == true) {
                    copyAssetFolder(assets, "model", destDir.absolutePath)
                }
                val m = Model(destDir.absolutePath)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    model = m
                    sharedModel = m
                    recognizerSetup()
                }
            } catch (e: Exception) {
                Log.e("WakeWord", "Failed to manually unpack/load model: ${e.message}", e)
            }
        }
        
        // Background Pre-warming of Gemini Nano
        CoroutineScope(Dispatchers.Main).launch {
            LLMManager.autoInitialize(applicationContext, callback = object : LLMManager.InitCallback {
                override fun onSuccess() {
                    CoroutineScope(Dispatchers.Main).launch {
                        LLMManager.prewarm(applicationContext)
                    }
                }
                override fun onError(e: Exception) {
                    Log.e("WakeWord", "Background LLM Init Failed", e)
                }
            })
        }
    }

    private fun updateWakeWord() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        wakeWord = prefs.getString("wake_word", "hey auto")?.lowercase() ?: "hey auto"
    }

    private var customAudioRecord: android.media.AudioRecord? = null
    private var customRecognizer: Recognizer? = null
    private var isRecording = false

    private fun recognizerSetup() {
        try {
            if (model == null) {
                Log.w("WakeWord", "Skipping recognizerSetup because model is null. Waiting for unpack.")
                return
            }
            customRecognizer = Recognizer(model, 16000.0f)
            startCustomListening()
        } catch (e: Exception) {
            Log.e("WakeWord", "Failed to init recognizer: ${e.message}")
        }
    }
    private var listeningJob: kotlinx.coroutines.Job? = null
    
    private fun startCustomListening() {
        if (isRecording) return
        isRecording = true
        listeningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val bufferSize = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT) * 2
                
                // Use HOTWORD (1999) to explicitly bypass CarAudioManager media ducking/silencing
                customAudioRecord = android.media.AudioRecord(1999, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                if (customAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e("WakeWord", "Failed to init HOTWORD AudioRecord! Attempting MIC fallback...")
                    customAudioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                }
                
                customAudioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                
                while (isRecording) {
                    val readSize = customAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        if (customRecognizer?.acceptWaveForm(buffer, readSize) == true) {
                            val result = customRecognizer?.result
                            if (result != null) checkWakeWord(result)
                        } else {
                            val partial = customRecognizer?.partialResult
                            if (partial != null) checkWakeWord(partial)
                        }
                    } else if (readSize < 0) {
                        Log.e("WakeWord", "AudioRecord read error: $readSize")
                        delay(1000) // Sleep and try to recover
                    }
                }
            } catch (e: Exception) {
                Log.e("WakeWord", "Custom listening loop error: ${e.message}")
            } finally {
                try {
                    customAudioRecord?.stop()
                    customAudioRecord?.release()
                } catch (e: Exception) {}
                customAudioRecord = null
            }
        }
    }
    private var restartJob: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateWakeWord()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        try {
            customAudioRecord?.stop()
            customAudioRecord?.release()
        } catch(e: Exception) {}
        customAudioRecord = null
        customRecognizer?.close()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkWakeWord(hypothesis: String) {
        val lowerHypothesis = hypothesis.lowercase()
        try {
            if (lowerHypothesis.contains("\"partial\"")) {
                val partial = org.json.JSONObject(hypothesis).getString("partial")
                if (partial.isNotEmpty()) {
                    val intent = Intent("com.example.gemininano.VOSK_PARTIAL")
                    intent.putExtra("partial", partial)
                    sendBroadcast(intent)
                }
            } else if (lowerHypothesis.contains("\"text\"")) {
                val text = org.json.JSONObject(hypothesis).getString("text")
                if (text.isNotEmpty()) {
                    val intent = Intent("com.example.gemininano.VOSK_RESULT")
                    intent.putExtra("text", text)
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {}
        val isMatch = lowerHypothesis.contains(wakeWord) || 
                      (wakeWord == "hey auto" && (lowerHypothesis.contains("hey otto") || 
                                                  lowerHypothesis.contains("hey out") || 
                                                  lowerHypothesis.contains("hey or no") || 
                                                  lowerHypothesis.contains("hey or don't") ||
                                                  lowerHypothesis.contains("hey i have a hot")))
        
        if (isMatch) {
            Log.d("WakeWord", "Wake word detected: $wakeWord")
            AssistantVoiceInteractionService.triggerSession(this@WakeWordService)
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String) {
        val files = assetManager.list(fromAssetPath)
        if (files.isNullOrEmpty()) {
            assetManager.open(fromAssetPath).use { input ->
                java.io.File(toPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            java.io.File(toPath).mkdirs()
            for (file in files) {
                copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
            }
        }
    }
}

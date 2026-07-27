package com.tcs.vehicleassistant

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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        var sharedModel: Model? = null
    }

    private var model: Model? = null
    private var wakeWord = "hey auto"

    override fun onCreate() {
        super.onCreate()
        
        val channelId = "wake_word_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Word Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Vehicle Assistant")
            .setContentText("Listening for wake word...")
            .setSmallIcon(R.drawable.ic_assistant_premium)
            .build()
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

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
        
        // Background LLM prewarm — short delay so first UI paint wins, then model loads
        // for the real agent query path.
        CoroutineScope(Dispatchers.Main).launch {
            delay(2_000)
            LLMManager.autoInitialize(applicationContext, callback = object : LLMManager.InitCallback {
                override fun onSuccess() {
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
                
                // Use VOICE_RECOGNITION source to enable hardware Acoustic Echo Cancellation (AEC).
                // This prevents the vehicle's own music/TTS from drowning out the user's voice.
                customAudioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                if (customAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e("WakeWord", "Failed to init MIC AudioRecord!")
                }
                
                customAudioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                
                var loopCount = 0
                while (isRecording) {
                    val readSize = customAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        loopCount++
                        if (loopCount % 20 == 0) {
                            val maxAmplitude = buffer.maxOrNull() ?: 0
                            Log.d("WakeWord", "Audio buffer max amplitude: $maxAmplitude")
                        }
                        
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
        if (intent?.action == "ACTION_RESTART_LISTENING") {
            restartJob?.cancel()
            restartJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Wait 50ms to ensure the Assistant's SpeechRecognizer has fully released the mic hardware
                    // before the WakeWord engine grabs it again.
                    delay(50)
                    
                    // 1. Tell IO thread to stop
                    isRecording = false
                    
                    // 2. Unblock the IO thread's AudioRecord.read() by stopping the microphone
                    try {
                        customAudioRecord?.stop()
                    } catch(e: Exception) {}
                    
                    // 3. WAIT for the IO thread to fully exit the acceptWaveForm loop!
                    listeningJob?.join()
                    
                    // 4. Safely close the C++ Recognizer now that no thread is using it
                    try {
                        customRecognizer?.close()
                    } catch (e: Exception) {}
                    customRecognizer = null
                    
                    recognizerSetup()
                    Log.d("WakeWord", "Restarting listener loop after RESTART intent...")
                    delay(50)
                    startCustomListening()
                } catch (e: Exception) {
                    Log.e("WakeWord", "Failed to restart: ${e.message}")
                }
            }
        } else if (intent?.action == "ACTION_STOP_LISTENING") {
            Log.d("WakeWord", "ACTION_STOP_LISTENING received. Stopping HOTWORD loop.")
            isRecording = false
            try {
                customAudioRecord?.stop()
                customAudioRecord?.release()
            } catch (e: Exception) {}
            customAudioRecord = null
        }
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

    private fun checkWakeWord(hypothesis: String) {
        val lowerHypothesis = hypothesis.lowercase()
        if (lowerHypothesis.contains("text") || lowerHypothesis.contains("partial")) {
            Log.d("WakeWord", "Vosk heard: $hypothesis")
        }
        val isMatch = lowerHypothesis.contains(wakeWord) || 
                      lowerHypothesis.contains("hey nissan") ||
                      lowerHypothesis.contains("nissan") ||
                      lowerHypothesis.contains("hey nice") ||
                      lowerHypothesis.contains("hey me") ||
                      lowerHypothesis.contains("hey listen") ||
                      lowerHypothesis.contains("hey lisa") ||
                      lowerHypothesis.contains("hey mason") ||
                      lowerHypothesis.contains("hey nathan") ||
                      lowerHypothesis.contains("hey missing") ||
                      lowerHypothesis.contains("hey auto") ||
                      lowerHypothesis.contains("hey otto") || 
                      lowerHypothesis.contains("hey out") || 
                      lowerHypothesis.contains("hey miss") ||
                      lowerHypothesis.contains("hey reason") ||
                      lowerHypothesis.contains("hey recent") ||
                      lowerHypothesis.contains("hey decent") ||
                      lowerHypothesis.contains("hey sam") ||
                      lowerHypothesis.contains("hey sun") ||
                      lowerHypothesis.contains("hey son") ||
                      lowerHypothesis.contains("hey i have a hot") ||
                      lowerHypothesis.contains("haney") ||
                      lowerHypothesis.contains("nisa") ||
                      lowerHypothesis.contains("haney sir")
        
        if (isMatch) {
            Log.d("WakeWord", "Wake word detected: $wakeWord")
            AssistantVoiceInteractionService.triggerSession(this@WakeWordService)
            
            // Stop listening. The AssistantSession will explicitly send ACTION_RESTART_LISTENING when it hides.
            isRecording = false
            try {
                customAudioRecord?.stop()
                customAudioRecord?.release()
            } catch (e: Exception) {}
            customAudioRecord = null
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

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

        fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String) {
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

        @Synchronized
        fun ensureModel(context: Context): Model? {
            if (sharedModel != null) return sharedModel
            return try {
                val destDir = java.io.File(context.filesDir, "model")
                if (!destDir.exists() || destDir.listFiles()?.isEmpty() == true) {
                    copyAssetFolder(context.assets, "model", destDir.absolutePath)
                }
                val m = Model(destDir.absolutePath)
                sharedModel = m
                m
            } catch (e: Exception) {
                Log.e("WakeWord", "Failed to load model in ensureModel: ${e.message}", e)
                null
            }
        }
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
        
        // Background Pre-warming of Gemini Nano and Soniqo
        // DELETED: Do not autoInitialize LLMManager here. It causes a dual-process OOM crash 
        // when the main app also tries to load the model. The LLM should only be loaded in the main process.


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
            
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            wakeWord = prefs.getString("wake_word", "hey nissan") ?: "hey nissan"
            val configuredWord = wakeWord.lowercase().trim()

            // Strictly constrain Vosk acoustic search space to "Hey Nissan" and the user-configured setting wake word
            val grammarSet = setOf("hey nissan", "nissan", configuredWord, "[unk]")
            val grammarJson = grammarSet.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
            
            Log.d("WakeWord", "Setting up Vosk recognizer with strict grammar: $grammarJson")
            customRecognizer = Recognizer(model, 16000.0f, grammarJson)
            startCustomListening()
        } catch (e: Exception) {
            Log.e("WakeWord", "Failed to init recognizer: ${e.message}")
        }
    }
    private var listeningJob: kotlinx.coroutines.Job? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    
    private fun startCustomListening() {
        if (isRecording) return
        isRecording = true
        listeningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val bufferSize = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT) * 2
                
                customAudioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                if (customAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e("WakeWord", "Failed to init MIC AudioRecord! Permission missing or mic in use.")
                    isRecording = false
                    return@launch
                }
                
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = customAudioRecord?.audioSessionId?.let { android.media.audiofx.NoiseSuppressor.create(it) }
                    noiseSuppressor?.enabled = true
                }
                
                customAudioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                
                var loopCount = 0
                var framesSinceLastSpeech = 100 
                val SPEECH_THRESHOLD = 500 // 16-bit PCM threshold

                while (isRecording) {
                    val readSize = customAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val absVal = Math.abs(buffer[i].toInt())
                            if (absVal > maxAmp) maxAmp = absVal
                        }

                        if (maxAmp > SPEECH_THRESHOLD) {
                            framesSinceLastSpeech = 0
                        } else {
                            framesSinceLastSpeech++
                        }

                        if (framesSinceLastSpeech < 30) {
                            val isCompleted = customRecognizer?.acceptWaveForm(buffer, readSize) ?: false
                            if (isCompleted) {
                                val hypothesis = customRecognizer?.result ?: ""
                                checkWakeWord(hypothesis)
                            } else {
                                if (loopCount % 10 == 0) {
                                    val partialText = customRecognizer?.partialResult ?: ""
                                    checkWakeWord(partialText)
                                }
                            }
                        }
                    }
                    loopCount++
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
                    stopCustomListening()
                    delay(400)
                    startCustomListening()
                } catch (e: Exception) {
                    Log.e("WakeWord", "Failed to restart listening", e)
                }
            }
            return START_STICKY
        }
        
        val action = intent?.action ?: "ACTION_START"
        if (action == "ACTION_STOP") {
            isRecording = false
            stopCustomListening()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1001, createNotification())

        if (model == null) {
            CoroutineScope(Dispatchers.IO).launch {
                model = ensureModel(applicationContext)
                withContext(Dispatchers.Main) {
                    recognizerSetup()
                }
            }
        } else {
            recognizerSetup()
        }
        return START_STICKY
    }

    private fun stopCustomListening() {
        isRecording = false
        try {
            listeningJob?.cancel()
            listeningJob = null
            customAudioRecord?.stop()
            customAudioRecord?.release()
        } catch (e: Exception) {
            Log.e("WakeWord", "Failed to stop AudioRecord", e)
        }
        customAudioRecord = null
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
        val configuredWord = wakeWord.lowercase().trim()

        if (lowerHypothesis.contains("text") || lowerHypothesis.contains("partial")) {
            Log.d("WakeWord", "Vosk heard: $hypothesis")
        }

        val isMatch = lowerHypothesis.contains("hey nissan") || 
                      lowerHypothesis.contains("nissan") ||
                      (configuredWord.isNotEmpty() && lowerHypothesis.contains(configuredWord))
        
        if (isMatch) {
            Log.d("WakeWord", "Wake word detected: $lowerHypothesis (matches configured: '$configuredWord')")
            sendBroadcast(Intent("com.tcs.vehicleassistant.WAKE_WORD_DETECTED").setPackage(packageName))
            
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

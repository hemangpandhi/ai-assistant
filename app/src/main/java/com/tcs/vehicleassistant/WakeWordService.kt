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
        CoroutineScope(Dispatchers.Main).launch {
            LLMManager.autoInitialize(applicationContext, callback = object : LLMManager.InitCallback {
                override fun onSuccess() {
                    // Deliberately removed LLMManager.prewarm() because it causes 
                    // a dual-process OOM crash when the main app also tries to load the model.
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
            
            // Removed strict grammar to allow the fuzzy matching logic in checkWakeWord to work properly
            // for different accents that might sound like "hey listen", "hey mason", etc.
            customRecognizer = Recognizer(model, 16000.0f)
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
                
                // IMPROVEMENT: Explicitly attach a software noise suppressor to filter out emulator/cabin static
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = customAudioRecord?.audioSessionId?.let { android.media.audiofx.NoiseSuppressor.create(it) }
                    noiseSuppressor?.enabled = true
                }
                
                customAudioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                
                var loopCount = 0
                var framesSinceLastSpeech = 100 // Initialize high to start in idle state
                val SPEECH_THRESHOLD = 500 // 16-bit PCM threshold

                while (isRecording) {
                    val readSize = customAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        loopCount++
                        
                        // 1. Calculate Max Amplitude for VAD
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val amp = Math.abs(buffer[i].toInt())
                            if (amp > maxAmp) maxAmp = amp
                        }
                        
                        if (loopCount % 20 == 0) {
                            Log.d("WakeWord", "Audio buffer max amplitude: $maxAmp")
                        }
                        
                        // 2. VAD Logic
                        if (maxAmp > SPEECH_THRESHOLD) {
                            framesSinceLastSpeech = 0
                        } else {
                            framesSinceLastSpeech++
                        }
                        
                        // 3. Only invoke heavy Vosk recognizer if someone is speaking (or recently stopped)
                        // Hang time of ~20 frames (~1.6 seconds) to let Vosk process the end of the sentence
                        if (framesSinceLastSpeech < 20) {
                            if (customRecognizer?.acceptWaveForm(buffer, readSize) == true) {
                                val result = customRecognizer?.result
                                if (result != null) checkWakeWord(result)
                            } else {
                                val partial = customRecognizer?.partialResult
                                if (partial != null) checkWakeWord(partial)
                            }
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
            // Must wait for the IO thread to fully exit acceptWaveForm before closing the C++ recognizer
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    customAudioRecord?.stop()
                } catch (e: Exception) {}
                // Wait for the IO thread to finish so it's not mid-acceptWaveForm
                listeningJob?.join()
                listeningJob = null
                try {
                    customAudioRecord?.release()
                } catch (e: Exception) {}
                customAudioRecord = null
                // Safely close the C++ recognizer now that no thread is using it
                try {
                    customRecognizer?.close()
                } catch (e: Exception) {}
                customRecognizer = null
                Log.d("WakeWord", "WakeWord fully stopped and recognizer released.")
            }
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
            sendBroadcast(Intent("com.tcs.vehicleassistant.WAKE_WORD_DETECTED").setPackage(packageName))
            
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

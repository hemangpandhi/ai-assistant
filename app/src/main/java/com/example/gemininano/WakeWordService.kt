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

        StorageService.unpack(this, "model", "model",
            { m: Model ->
                model = m
                sharedModel = m
                recognizerSetup()
            },
            { exception: java.io.IOException -> 
                Log.e("WakeWord", "Failed to unpack model: ${exception.message}") 
            }
        )
        
        // Background Pre-warming of Gemini Nano
        CoroutineScope(Dispatchers.Main).launch {
            LLMManager.autoInitialize(applicationContext, callback = object : LLMManager.InitCallback {
                override fun onSuccess() {
                    CoroutineScope(Dispatchers.Main).launch {
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
        if (intent?.action == "ACTION_RESTART_LISTENING") {
            restartJob?.cancel()
            restartJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Wait 2500ms to allow async media apps (like Spotify) to claim Audio Focus 
                    // and trigger AudioFlinger DSP routing changes before we grab the mic.
                    delay(2500)
                    
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
                    Log.d("WakeWord", "Restarting listener after Assistant UI closed")
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
    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkWakeWord(hypothesis: String) {
        val lowerHypothesis = hypothesis.lowercase()
        if (lowerHypothesis.contains("text") || lowerHypothesis.contains("partial")) {
            Log.d("WakeWord", "Vosk heard: $hypothesis")
        }
        val isMatch = lowerHypothesis.contains(wakeWord) || 
                      (wakeWord == "hey auto" && (lowerHypothesis.contains("hey otto") || 
                                                  lowerHypothesis.contains("hey out") || 
                                                  lowerHypothesis.contains("hey or no") || 
                                                  lowerHypothesis.contains("hey or don't") ||
                                                  lowerHypothesis.contains("hey i have a hot")))
        
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


}

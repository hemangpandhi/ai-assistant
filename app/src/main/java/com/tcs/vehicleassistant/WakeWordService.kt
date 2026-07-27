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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.abs

/**
 * Always-on wake-word listener with duty-cycled Vosk processing when the cabin is quiet.
 * Owns its own [serviceJob] — independent of [com.tcs.vehicleassistant.core.AgentRuntime].
 */
class WakeWordService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        var sharedModel: Model? = null
        private const val TAG = "WakeWord"
        /** PCM samples below this are treated as silence for duty-cycling. */
        private const val SILENCE_THRESHOLD = 180
        /** When silent, run recognizer on 1 of every N buffers. */
        private const val SILENT_DUTY_SKIP = 4
    }

    private val serviceJob = SupervisorJob()
    private val mainScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(serviceJob + Dispatchers.IO)

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

        ioScope.launch {
            try {
                val destDir = java.io.File(filesDir, "model")
                if (!destDir.exists() || destDir.listFiles()?.isEmpty() == true) {
                    copyAssetFolder(assets, "model", destDir.absolutePath)
                }
                val m = Model(destDir.absolutePath)
                withContext(Dispatchers.Main) {
                    model = m
                    sharedModel = m
                    recognizerSetup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to manually unpack/load model: ${e.message}", e)
            }
        }

        // Background LLM prewarm — short delay so first UI paint wins.
        mainScope.launch {
            delay(2_000)
            LLMManager.autoInitialize(applicationContext, callback = object : LLMManager.InitCallback {
                override fun onSuccess() {
                }
                override fun onError(e: Exception) {
                    Log.e(TAG, "Background LLM Init Failed", e)
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
                Log.w(TAG, "Skipping recognizerSetup because model is null. Waiting for unpack.")
                return
            }
            customRecognizer = Recognizer(model, 16000.0f)
            startCustomListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init recognizer: ${e.message}")
        }
    }

    private var listeningJob: Job? = null

    private fun startCustomListening() {
        if (isRecording) return
        isRecording = true
        listeningJob = ioScope.launch {
            try {
                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                ) * 2

                customAudioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )

                if (customAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to init MIC AudioRecord!")
                }

                customAudioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)

                var loopCount = 0
                var silentBuffers = 0
                while (isRecording) {
                    val readSize = customAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        loopCount++
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val a = abs(buffer[i].toInt())
                            if (a > maxAmplitude) maxAmplitude = a
                        }
                        if (loopCount % 20 == 0) {
                            Log.d(TAG, "Audio buffer max amplitude: $maxAmplitude")
                        }

                        // Duty-cycle: when parked-quiet, skip most Vosk acceptWaveForm calls.
                        if (maxAmplitude < SILENCE_THRESHOLD) {
                            silentBuffers++
                            if (silentBuffers % SILENT_DUTY_SKIP != 0) {
                                continue
                            }
                        } else {
                            silentBuffers = 0
                        }

                        if (customRecognizer?.acceptWaveForm(buffer, readSize) == true) {
                            val result = customRecognizer?.result
                            if (result != null) checkWakeWord(result)
                        } else {
                            val partial = customRecognizer?.partialResult
                            if (partial != null) checkWakeWord(partial)
                        }
                    } else if (readSize < 0) {
                        Log.e(TAG, "AudioRecord read error: $readSize")
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Custom listening loop error: ${e.message}")
            } finally {
                try {
                    customAudioRecord?.stop()
                    customAudioRecord?.release()
                } catch (_: Exception) {
                }
                customAudioRecord = null
            }
        }
    }

    private var restartJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_RESTART_LISTENING") {
            restartJob?.cancel()
            restartJob = mainScope.launch {
                try {
                    delay(50)
                    isRecording = false
                    try {
                        customAudioRecord?.stop()
                    } catch (_: Exception) {
                    }
                    listeningJob?.join()
                    try {
                        customRecognizer?.close()
                    } catch (_: Exception) {
                    }
                    customRecognizer = null
                    recognizerSetup()
                    Log.d(TAG, "Restarting listener loop after RESTART intent...")
                    delay(50)
                    startCustomListening()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart: ${e.message}")
                }
            }
        } else if (intent?.action == "ACTION_STOP_LISTENING") {
            Log.d(TAG, "ACTION_STOP_LISTENING received. Stopping HOTWORD loop.")
            restartJob?.cancel()
            restartJob = mainScope.launch {
                try {
                    isRecording = false
                    try {
                        customAudioRecord?.stop()
                    } catch (_: Exception) {
                    }
                    listeningJob?.join()
                    try {
                        customAudioRecord?.release()
                    } catch (_: Exception) {
                    }
                    customAudioRecord = null
                    Log.d(TAG, "Hotword AudioRecord fully released")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop hotword cleanly: ${e.message}")
                }
            }
        }
        updateWakeWord()
        return START_STICKY
    }

    override fun onDestroy() {
        isRecording = false
        serviceJob.cancel()
        try {
            customAudioRecord?.stop()
            customAudioRecord?.release()
        } catch (_: Exception) {
        }
        customAudioRecord = null
        customRecognizer?.close()
        super.onDestroy()
    }

    private fun checkWakeWord(hypothesis: String) {
        val lowerHypothesis = hypothesis.lowercase()
        if (lowerHypothesis.contains("text") || lowerHypothesis.contains("partial")) {
            Log.d(TAG, "Vosk heard: $hypothesis")
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
            Log.d(TAG, "Wake word detected: $wakeWord")
            AssistantVoiceInteractionService.triggerSession(this@WakeWordService, fromHotword = true)

            // Stop listening. The AssistantSession will explicitly send ACTION_RESTART_LISTENING when it hides.
            isRecording = false
            try {
                customAudioRecord?.stop()
                customAudioRecord?.release()
            } catch (_: Exception) {
            }
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

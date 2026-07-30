package com.tcs.vehicleassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tcs.vehicleassistant.core.AssistantConfig
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
import java.io.File

/**
 * Foreground service for offline hotword detection using Vosk with a constrained grammar, so it
 * only ever fires on the configured wake word.
 *
 * The service owns the microphone whenever it is listening, which means it must reliably hand the
 * microphone off to the speech recognizer when a voice session opens and take it back when the
 * session ends. Those transitions are driven by [AssistantConfig.WakeWordAction]; the action
 * strings live there because the session used to send `ACTION_STOP_LISTENING` while this service
 * only recognised `ACTION_STOP`, so the stop request fell through to the start branch and the
 * wake-word microphone was never released — leaving two components contending for the mic.
 */
class WakeWordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWord"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wake_word_channel"

        @Volatile
        private var sharedModel: Model? = null

        fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String) {
            val files = assetManager.list(fromAssetPath)
            if (files.isNullOrEmpty()) {
                assetManager.open(fromAssetPath).use { input ->
                    File(toPath).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                File(toPath).mkdirs()
                for (file in files) {
                    copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
                }
            }
        }

        /**
         * Optional sideload for the Vosk wake pack (extracted model tree). Prefer this over
         * packaging a ~200 MB archive in assets when pushing to device:
         * `adb push model/ /data/local/tmp/vosk/` (must contain `am/`, `conf/`, `graph/`, …).
         * If missing, falls back to assets `model/` as before — wake still works without sideload.
         */
        private const val VOSK_SIDELOAD_DIR = "/data/local/tmp/vosk"

        @Synchronized
        fun ensureModel(context: Context): Model? {
            sharedModel?.let { return it }
            return try {
                val sideload = File(VOSK_SIDELOAD_DIR)
                if (sideload.isDirectory && File(sideload, "am").isDirectory) {
                    Log.i(TAG, "Loading Vosk wake model from $VOSK_SIDELOAD_DIR")
                    return Model(sideload.absolutePath).also { sharedModel = it }
                }
                val destDir = File(context.filesDir, "model")
                if (!destDir.exists() || destDir.listFiles()?.isEmpty() == true) {
                    copyAssetFolder(context.assets, "model", destDir.absolutePath)
                }
                Model(destDir.absolutePath).also { sharedModel = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Vosk model: ${e.message}", e)
                null
            }
        }

        @Synchronized
        fun releaseModel() {
            try {
                sharedModel?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close Vosk model", e)
            }
            sharedModel = null
        }

        /**
         * True when [transcript] contains the configured wake word as a fresh phrase.
         *
         * Matching strips Vosk `[unk]` tokens first. A bare `contains` on the raw decoder string
         * rematched after RESTART when the recognizer was not reset — e.g.
         * `"hey [unk] hey nissan"` normalizes to `"hey hey nissan"`, which still contains
         * `"hey nissan"` once. Reject when the wake phrase's leading token repeats more often
         * than it appears in the configured phrase itself.
         */
        fun matchesWakeWord(transcript: String, configuredWakeWord: String): Boolean {
            val configured = configuredWakeWord.lowercase().trim()
            if (configured.isEmpty()) return false
            val text = normalizeTranscript(transcript)
            if (text.isEmpty()) return false
            if (!text.contains(configured)) return false

            val lead = configured.substringBefore(' ')
            if (lead.isNotEmpty()) {
                val leadRegex = Regex("""\b${Regex.escape(lead)}\b""")
                val inConfigured = leadRegex.findAll(configured).count()
                val inText = leadRegex.findAll(text).count()
                if (inText > inConfigured) return false
            }

            val first = text.indexOf(configured)
            val second = text.indexOf(configured, first + configured.length)
            return second < 0
        }

        /** Lowercases, drops `[unk]`, and collapses whitespace. */
        fun normalizeTranscript(transcript: String): String =
            transcript.lowercase()
                .replace(AssistantConfig.WakeWord.UNKNOWN_TOKEN, " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Extracts the transcript from a Vosk `{"text": ...}` or `{"partial": ...}` result. */
        fun extractTranscript(voskJson: String): String =
            Regex(""""(?:text|partial)"\s*:\s*"([^"]*)"""")
                .find(voskJson.lowercase())
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
    }

    private var model: Model? = null
    private var wakeWord = AssistantConfig.WakeWord.DEFAULT_WAKE_WORD
    private var customRecognizer: Recognizer? = null
    private var customAudioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false

    private var listeningJob: Job? = null
    private var restartJob: Job? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /** Elapsed realtime after which a match is allowed again. */
    @Volatile
    private var matchCooldownUntilElapsedMs = 0L

    /** Owned scope so every coroutine this service starts is cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        updateWakeWord()
    }

    private fun updateWakeWord() {
        val prefs = getSharedPreferences(AssistantConfig.PREFS_NAME, MODE_PRIVATE)
        wakeWord = prefs.getString(AssistantConfig.Prefs.WAKE_WORD, AssistantConfig.WakeWord.DEFAULT_WAKE_WORD)
            ?: AssistantConfig.WakeWord.DEFAULT_WAKE_WORD
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Wake Word Detection", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_display_name))
            .setContentText(getString(R.string.wake_word_notification_text, wakeWord))
            .setSmallIcon(R.drawable.ic_notification_waveform)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateWakeWord()
        val action = intent?.action

        if (AssistantConfig.WakeWordAction.isStop(action)) {
            Log.i(TAG, "Stop requested; releasing microphone and stopping service.")
            stopCustomListening()
            // stopSelf clears the pending startForeground obligation, so a stop that arrived via
            // startForegroundService does not need the notification posted first.
            stopSelf()
            return START_NOT_STICKY
        }

        // Every branch that keeps the service alive has to post the notification. Callers reach
        // this service through both startService and startForegroundService — the permission-grant
        // path uses the latter with a restart action — and a startForegroundService that is never
        // matched by startForeground is killed with ForegroundServiceDidNotStartInTimeException.
        if (!promoteToForeground()) return START_NOT_STICKY

        return when {
            AssistantConfig.WakeWordAction.isPause(action) -> {
                // A voice session is opening. Release the microphone but stay alive so the
                // session can hand it straight back when it finishes.
                Log.i(TAG, "Pause requested; releasing microphone for the speech recognizer.")
                restartJob?.cancel()
                stopCustomListening()
                resetRecognizer()
                START_STICKY
            }

            AssistantConfig.WakeWordAction.isRestart(action) -> {
                Log.i(TAG, "Restart requested; re-acquiring microphone.")
                restartJob?.cancel()
                restartJob = serviceScope.launch {
                    try {
                        stopCustomListening()
                        resetRecognizer()
                        delay(AssistantConfig.WakeWord.RESTART_DELAY_MS)
                        // Ignore the first window after resume: stale finals and media bleed.
                        matchCooldownUntilElapsedMs = maxOf(
                            matchCooldownUntilElapsedMs,
                            android.os.SystemClock.elapsedRealtime() +
                                AssistantConfig.WakeWord.POST_RESTART_IGNORE_MS
                        )
                        ensureRecognizerAndListen()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A newer RESTART cancelled this job; expected when finish/onHide race.
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart listening", e)
                    }
                }
                START_STICKY
            }

            else -> {
                serviceScope.launch { ensureRecognizerAndListen() }
                START_STICKY
            }
        }
    }

    /**
     * Posts the ongoing notification, or stops the service and returns false when it cannot.
     *
     * A `microphone` foreground service may not start without `RECORD_AUDIO` on API 34, so
     * promoting before the user grants the permission throws instead of merely failing to record.
     * There is nothing this service can do without the microphone, so it stops in that case.
     */
    private fun promoteToForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; wake word detection cannot run.")
            stopSelf()
            return false
        }
        return try {
            startForeground(NOTIFICATION_ID, createNotification())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter the foreground; stopping wake word detection.", e)
            stopSelf()
            false
        }
    }

    /** Loads the model if needed, rebuilds the constrained grammar, and starts the mic loop. */
    private suspend fun ensureRecognizerAndListen() {
        if (model == null) {
            model = withContext(Dispatchers.IO) { ensureModel(applicationContext) }
        }
        val loadedModel = model
        if (loadedModel == null) {
            Log.e(TAG, "Vosk model unavailable; wake word detection is disabled.")
            return
        }

        if (customRecognizer == null) {
            try {
                // Constraining the grammar to the wake word plus [unk] is what keeps false
                // triggers down; a free-form recognizer fired on unrelated speech.
                val configuredWord = wakeWord.lowercase().trim()
                    .ifEmpty { AssistantConfig.WakeWord.DEFAULT_WAKE_WORD }
                val grammarJson = setOf(configuredWord, AssistantConfig.WakeWord.UNKNOWN_TOKEN)
                    .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

                Log.d(TAG, "Vosk recognizer grammar: $grammarJson")
                customRecognizer = Recognizer(
                    loadedModel,
                    AssistantConfig.Audio.SAMPLE_RATE_HZ.toFloat(),
                    grammarJson
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init recognizer: ${e.message}", e)
                return
            }
        }

        startCustomListening()
    }

    private fun startCustomListening() {
        if (isRecording) return
        isRecording = true
        listeningJob = serviceScope.launch { runMicrophoneLoop() }
    }

    private suspend fun runMicrophoneLoop() {
        var record: AudioRecord? = null
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                AssistantConfig.Audio.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AssistantConfig.Audio.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            customAudioRecord = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed — mic permission missing or mic already in use.")
                return
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }

            record.startRecording()
            val buffer = ShortArray(bufferSize)
            var loopCount = 0
            var framesSinceLastSpeech = Int.MAX_VALUE / 2

            while (isRecording) {
                val readSize = record.read(buffer, 0, buffer.size)
                if (readSize <= 0) {
                    delay(10)
                    continue
                }

                var maxAmp = 0
                for (i in 0 until readSize) {
                    val absVal = Math.abs(buffer[i].toInt())
                    if (absVal > maxAmp) maxAmp = absVal
                }

                framesSinceLastSpeech = if (maxAmp > AssistantConfig.WakeWord.SPEECH_AMPLITUDE_THRESHOLD) {
                    0
                } else {
                    framesSinceLastSpeech + 1
                }

                // Only feed the recognizer around detected speech; decoding pure silence wasted
                // CPU continuously in a service that runs for the whole drive.
                if (framesSinceLastSpeech >= AssistantConfig.WakeWord.RECOGNITION_TAIL_FRAMES) {
                    loopCount++
                    continue
                }

                val recognizer = customRecognizer ?: break
                // Only finals may open the session. Partials on a sticky decoder re-fired the
                // previous wake phrase after RESTART (see "hey [unk] hey nissan" rematches).
                if (recognizer.acceptWaveForm(buffer, readSize)) {
                    handleTranscript(recognizer.result)
                    // Clear decoder state after every final so music/TTS cannot rematch leftovers.
                    resetRecognizer()
                }
                loopCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Microphone loop error: ${e.message}", e)
        } finally {
            // Release on every exit path. The previous version only released the noise
            // suppressor here, so an early return leaked the AudioRecord and kept the mic held.
            releaseAudioResources(record)
            isRecording = false
        }
    }

    private fun releaseAudioResources(record: AudioRecord?) {
        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release noise suppressor", e)
        }
        noiseSuppressor = null

        try {
            if (record?.state == AudioRecord.STATE_INITIALIZED) record.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop AudioRecord", e)
        }
        try {
            record?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release AudioRecord", e)
        }
        if (customAudioRecord === record) customAudioRecord = null
    }

    private fun stopCustomListening() {
        isRecording = false
        listeningJob?.cancel()
        listeningJob = null
        releaseAudioResources(customAudioRecord)
    }

    /** Drops in-flight Vosk hypotheses so a later RESTART cannot rematch a prior wake phrase. */
    private fun resetRecognizer() {
        try {
            customRecognizer?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset recognizer", e)
        }
    }

    override fun onDestroy() {
        isRecording = false
        stopCustomListening()
        try {
            customRecognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close recognizer", e)
        }
        customRecognizer = null
        model = null
        releaseModel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleTranscript(voskJson: String?) {
        val transcript = extractTranscript(voskJson.orEmpty())
        if (!matchesWakeWord(transcript, wakeWord)) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now < matchCooldownUntilElapsedMs) {
            Log.i(TAG, "Wake word ignored during cooldown: '$transcript'")
            return
        }

        Log.i(TAG, "Wake word matched: '$transcript'")
        matchCooldownUntilElapsedMs = now + AssistantConfig.WakeWord.POST_MATCH_COOLDOWN_MS
        sendBroadcast(
            Intent(AssistantConfig.WakeWordAction.DETECTED_BROADCAST).setPackage(packageName)
        )

        // Release the mic immediately so the session's speech recognizer can acquire it.
        stopCustomListening()
        resetRecognizer()
    }
}

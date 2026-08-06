package com.tcs.vehicleassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tcs.vehicleassistant.assistant.WakeWordPhrasePolicy
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

        /**
         * Required sideload for the Vosk wake pack (extracted model tree).
         * APK no longer packages `assets/model/` (~205 MB). Push via:
         * `adb push device_models/vosk/. /data/local/tmp/vosk/`
         * (must contain `am/`, `conf/`, `graph/`, …). See docs/MODEL_SIDELOAD.md.
         */
        private const val VOSK_SIDELOAD_DIR = "/data/local/tmp/vosk"

        @Synchronized
        fun ensureModel(@Suppress("UNUSED_PARAMETER") context: Context): Model? {
            sharedModel?.let { return it }
            return try {
                val sideload = File(VOSK_SIDELOAD_DIR)
                if (sideload.isDirectory && File(sideload, "am").isDirectory) {
                    Log.i(TAG, "Loading Vosk wake model from $VOSK_SIDELOAD_DIR")
                    return Model(sideload.absolutePath).also { sharedModel = it }
                }
                Log.e(
                    TAG,
                    "Vosk wake model missing under $VOSK_SIDELOAD_DIR (need am/). " +
                        "APK no longer packages assets/model; adb-push device_models/vosk/ — " +
                        "see docs/MODEL_SIDELOAD.md",
                )
                null
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
         * True when [transcript] matches the fixed allowlist
         * `(hey|hi|hello|ok|okay) + (iris|car|sora)` — see [WakeWordPhrasePolicy].
         */
        fun matchesWakeWord(transcript: String, configuredWakeWord: String): Boolean =
            WakeWordPhrasePolicy.matches(transcript, configuredWakeWord)

        /** Lowercases, drops `[unk]`, and collapses whitespace. */
        fun normalizeTranscript(transcript: String): String =
            WakeWordPhrasePolicy.normalizeTranscript(transcript)

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
    /** Grammar key used to build [customRecognizer]; rebuilt when the wake phrase set changes. */
    private var recognizerGrammarKey: String? = null
    private var kwsSpotter: com.k2fsa.sherpa.onnx.KeywordSpotter? = null
    private var kwsStream: com.k2fsa.sherpa.onnx.OnlineStream? = null

    private var restartJob: Job? = null

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

        if (AssistantConfig.isWakeWordDisabledForTest(this)) {
            Log.i(TAG, "WAKE_WORD_DISABLED_FOR_TEST — refusing to start Vosk; stopping.")
            stopCustomListening()
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

        val configuredWord = wakeWord.lowercase().trim()
            .ifEmpty { AssistantConfig.WakeWord.DEFAULT_WAKE_WORD }
        val phrases = WakeWordPhrasePolicy.grammarPhrases(configuredWord)
        val grammarKey = phrases.sorted().joinToString("|")
        if (customRecognizer != null && recognizerGrammarKey != grammarKey) {
            try {
                customRecognizer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close recognizer for grammar rebuild", e)
            }
            customRecognizer = null
            recognizerGrammarKey = null
        }

        if (customRecognizer == null) {
            try {
                // Constrained grammar: fixed (hey|hi|hello|ok|okay)+(iris|car|sora) + [unk].
                val grammarJson = phrases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

                Log.d(TAG, "Vosk recognizer grammar: $grammarJson")
                customRecognizer = Recognizer(
                    loadedModel,
                    AssistantConfig.Audio.SAMPLE_RATE_HZ.toFloat(),
                    grammarJson
                )
                recognizerGrammarKey = grammarKey
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init recognizer: ${e.message}", e)
                return
            }
        }

        startCustomListening()
    }

    private fun startCustomListening() {
        kwsSpotter = com.tcs.vehicleassistant.hardware.SherpaKwsManager.getKeywordSpotter(this)

        com.tcs.vehicleassistant.hardware.ear.ContinuousAudioPipeline.kwsSubscriber = { floatFrame, shortBuffer, readSize ->
            val spotter = kwsSpotter
            var matched = false
            if (spotter != null) {
                if (kwsStream == null) {
                    kwsStream = spotter.createStream()
                }
                val stream = kwsStream
                if (stream != null) {
                    val slice = if (readSize == floatFrame.size) floatFrame else floatFrame.copyOf(readSize)
                    stream.acceptWaveform(slice, 16000)

                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                    }
                    val result = spotter.getResult(stream)
                    if (result.keyword.isNotBlank()) {
                        val label = WakeWordPhrasePolicy.normalizeKwsKeyword(result.keyword)
                        // Never trust KWS blindly — only allowlisted phrases open the UI.
                        if (matchesWakeWord(label, wakeWord)) {
                            matched = true
                            spotter.reset(stream)
                            onWakeDetected(label)
                        } else {
                            Log.d(TAG, "KWS keyword ignored (not allowlisted): '${result.keyword}'")
                            spotter.reset(stream)
                        }
                    }
                }
            }

            // Vosk path: primary when KWS is unavailable, also covers frames KWS did not fire on.
            // Always feed PCM (constrained grammar is cheap). Checking partials matters — finals
            // often never arrive if the mic level stays under the old amplitude gate.
            val recognizer = customRecognizer
            if (!matched && recognizer != null) {
                if (recognizer.acceptWaveForm(shortBuffer, readSize)) {
                    handleTranscript(recognizer.result)
                    resetRecognizer()
                } else {
                    handleTranscript(recognizer.partialResult)
                }
            }
        }

        // Always (re)attach the subscriber above; only start the HAL loop if needed.
        if (!com.tcs.vehicleassistant.hardware.ear.ContinuousAudioPipeline.isRecording) {
            com.tcs.vehicleassistant.hardware.ear.ContinuousAudioPipeline.start(applicationContext)
        }
    }

    private fun stopCustomListening() {
        com.tcs.vehicleassistant.hardware.ear.ContinuousAudioPipeline.kwsSubscriber = null
        com.tcs.vehicleassistant.hardware.ear.ContinuousAudioPipeline.stop()
        kwsStream?.release()
        kwsStream = null
        try {
            com.tcs.vehicleassistant.hardware.SherpaKwsManager.release()
        } catch (_: Exception) {}
        kwsSpotter = null
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
        stopCustomListening()
        customRecognizer = null
        recognizerGrammarKey = null
        model = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleTranscript(voskJson: String?) {
        val transcript = extractTranscript(voskJson.orEmpty())
        if (!matchesWakeWord(transcript, wakeWord)) return
        onWakeDetected(transcript)
    }

    /** Cooldown + broadcast + mic release after a confirmed wake (Vosk match or Sherpa KWS). */
    private fun onWakeDetected(label: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < matchCooldownUntilElapsedMs) {
            Log.i(TAG, "Wake word ignored during cooldown: '$label'")
            return
        }

        Log.i(TAG, "Wake word matched: '$label'")
        matchCooldownUntilElapsedMs = now + AssistantConfig.WakeWord.POST_MATCH_COOLDOWN_MS
        sendBroadcast(
            Intent(AssistantConfig.WakeWordAction.DETECTED_BROADCAST).setPackage(packageName)
        )

        // Release the mic immediately so the session's speech recognizer can acquire it.
        stopCustomListening()
        resetRecognizer()
    }
}

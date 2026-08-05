package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.TtsVoiceCatalog
import com.tcs.vehicleassistant.hardware.ear.AssistantEar
import com.tcs.vehicleassistant.hardware.ear.EarSttCallbacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cabin audio facade (SRP split):
 * - **TTS** — Sherpa OfflineTts + AudioTrack (this class)
 * - **STT / mic** — [AssistantEar] (standby [android.media.AudioRecord], Silero, Sherpa Whisper
 *   or demoted Google offline [android.speech.SpeechRecognizer])
 */
class AndroidAudioManager(
    private val context: Context,
) : IAudioManager {

    private companion object {
        const val TAG = "AndroidAudioManager"
    }

    private val systemAudio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val ear = AssistantEar(context)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var offlineTts: OfflineTts? = null
    @Volatile private var ttsSpeakerId: Int = 0
    @Volatile private var ttsSpeed: Float = 1.0f
    @Volatile private var loadedVoiceId: String = TtsVoiceCatalog.BUNDLED_AMY_ID

    private var onTtsStart: ((String) -> Unit)? = null
    private var onTtsDone: ((String) -> Unit)? = null
    private var onTtsError: ((String) -> Unit)? = null
    private var onTtsRangeStart: ((String, Int, Int, Int) -> Unit)? = null

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ttsChannel = Channel<suspend CoroutineScope.() -> Unit>(Channel.UNLIMITED)
    private var ttsLoopJob: Job? = null
    private var globalAudioTrack: AudioTrack? = null
    private val speechDrainWaiters =
        java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.CompletableDeferred<Unit>>()

    init {
        ear.setCallbacks(EarSttCallbacks())
        startTtsLoop()
    }

    // ── Focus ───────────────────────────────────────────────────────────────

    private fun requestFocusAndMaxVolume() {
        try {
            // STREAM_ASSISTANT = 11 on Automotive
            val maxVol = systemAudio.getStreamMaxVolume(11)
            systemAudio.setStreamVolume(11, maxVol, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set assistant volume", e)
        }
    }

    private fun abandonFocus() {
        // Focus is owned by AssistantSessionAudioFocus — do not fight it here.
    }

    // ── TTS ─────────────────────────────────────────────────────────────────

    private fun copyEspeakData(): String {
        val outDir = java.io.File(context.filesDir, "espeak-ng-data")
        if (outDir.exists() && java.io.File(outDir, "phontab").exists()) return outDir.absolutePath
        outDir.mkdirs()

        fun copyAssetsToDir(assetPath: String, destDir: java.io.File) {
            val list = context.assets.list(assetPath)
            if (list.isNullOrEmpty()) {
                try {
                    context.assets.open(assetPath).use { input ->
                        java.io.FileOutputStream(destDir).use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                }
            } else {
                destDir.mkdirs()
                for (file in list) {
                    copyAssetsToDir("$assetPath/$file", java.io.File(destDir, file))
                }
            }
        }
        copyAssetsToDir("sherpa-onnx-tts/espeak-ng-data", outDir)
        return outDir.absolutePath
    }

    private fun readVoicePrefs() {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        ttsSpeed = prefs.getFloat(AssistantConfig.Prefs.VOICE_RATE, 1.0f).coerceIn(0.5f, 2.0f)
        ttsSpeakerId = prefs.getInt(AssistantConfig.Prefs.TTS_SPEAKER_ID, 0).coerceAtLeast(0)
    }

    private fun buildOfflineTts(): OfflineTts {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val voice = TtsVoiceCatalog.findById(context, prefs.getString(AssistantConfig.Prefs.TTS_VOICE_ID, null))
        val espeakDataPath = copyEspeakData()
        val vitsConfig = OfflineTtsVitsModelConfig(
            model = voice.modelPath,
            tokens = voice.tokensPath,
            lexicon = "",
            dataDir = espeakDataPath,
            dictDir = "",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f,
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 1,
            debug = false,
            provider = "cpu",
        )
        val config = OfflineTtsConfig(model = modelConfig, ruleFsts = "", maxNumSentences = 1)
        loadedVoiceId = voice.id
        Log.i(TAG, "Loading TTS voice id=${voice.id} assets=${voice.fromAssets}")
        val tts = if (voice.fromAssets) {
            OfflineTts(context.assets, config)
        } else {
            OfflineTts(assetManager = null, config = config)
        }
        val speakers = tts.numSpeakers().coerceAtLeast(1)
        ttsSpeakerId = ttsSpeakerId.coerceIn(0, speakers - 1)
        return tts
    }

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
        try {
            readVoicePrefs()
            offlineTts = buildOfflineTts()
            prewarmEar()
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize offline TTS", e)
            try {
                val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(AssistantConfig.Prefs.TTS_VOICE_ID, TtsVoiceCatalog.BUNDLED_AMY_ID).apply()
                offlineTts = buildOfflineTts()
                prewarmEar()
                onSuccess()
            } catch (fallback: Exception) {
                Log.e(TAG, "Bundled Amy TTS also failed", fallback)
                onError()
            }
        }
    }

    override fun reloadTtsFromPrefs() {
        ttsChannel.trySend {
            try {
                readVoicePrefs()
                offlineTts?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release previous TTS", e)
            }
            offlineTts = null
            try {
                offlineTts = buildOfflineTts()
                Log.i(TAG, "Reloaded TTS voice=$loadedVoiceId sid=$ttsSpeakerId speed=$ttsSpeed")
            } catch (e: Exception) {
                Log.e(TAG, "TTS reload failed; restoring Amy", e)
                try {
                    val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(AssistantConfig.Prefs.TTS_VOICE_ID, TtsVoiceCatalog.BUNDLED_AMY_ID).apply()
                    offlineTts = buildOfflineTts()
                } catch (fallback: Exception) {
                    Log.e(TAG, "TTS Amy fallback failed", fallback)
                    offlineTts = null
                    onTtsError?.invoke("Cabin voice failed to load. Check TTS settings.")
                }
            }
        }
    }

    private fun startTtsLoop() {
        ttsLoopJob = ttsScope.launch {
            for (task in ttsChannel) {
                if (!isActive) break
                try {
                    task()
                } catch (_: CancellationException) {
                    break
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        ttsChannel.trySend {
            requestFocusAndMaxVolume()
            try {
                val cleanText = text.replace("*", "").replace("#", "").replace("_", "")
                val sentences = cleanText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                if (sentences.isEmpty()) return@trySend

                var isFirstSentence = true
                for (sentence in sentences) {
                    if (!isActive) break
                    val audio = offlineTts?.generate(sentence, sid = ttsSpeakerId, speed = ttsSpeed) ?: continue
                    val samples = audio.samples
                    val sampleRate = audio.sampleRate

                    if (globalAudioTrack == null || globalAudioTrack!!.sampleRate != sampleRate) {
                        globalAudioTrack?.release()
                        val minBufferSize = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        val audioFormat = android.media.AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                        globalAudioTrack = AudioTrack.Builder()
                            .setAudioAttributes(audioAttributes)
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(minBufferSize * 4)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        globalAudioTrack?.play()
                    }

                    if (globalAudioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        globalAudioTrack?.play()
                    }

                    if (isFirstSentence) {
                        isFirstSentence = false
                        withContext(Dispatchers.Main) { onTtsStart?.invoke(utteranceId) }
                    }

                    val shortSamples = ShortArray(samples.size)
                    for (i in samples.indices) {
                        var v = samples[i]
                        if (v > 1.0f || v < -1.0f) {
                            if (v > 32767f) v = 32767f
                            if (v < -32768f) v = -32768f
                        } else {
                            v *= 32767f
                        }
                        shortSamples[i] = v.toInt().toShort()
                    }
                    globalAudioTrack?.write(shortSamples, 0, shortSamples.size)
                }

                if (isActive) {
                    withContext(Dispatchers.Main) { onTtsDone?.invoke(utteranceId) }
                }
                abandonFocus()
            } catch (_: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) { onTtsError?.invoke(utteranceId) }
                }
                abandonFocus()
            }
        }
    }

    override suspend fun waitUntilFinishedSpeaking() {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        val result = ttsChannel.trySend {
            delay(500)
            deferred.complete(Unit)
        }
        if (!result.isSuccess) return

        speechDrainWaiters.add(deferred)
        try {
            val drained = withTimeoutOrNull(AssistantConfig.Audio.SPEECH_DRAIN_TIMEOUT_MS) {
                deferred.await()
            }
            if (drained == null) {
                Log.w(TAG, "Speech queue did not drain in time; continuing.")
            }
        } finally {
            speechDrainWaiters.remove(deferred)
        }
    }

    private fun releaseSpeechDrainWaiters() {
        val waiters = speechDrainWaiters.toList()
        speechDrainWaiters.clear()
        for (waiter in waiters) waiter.complete(Unit)
    }

    override fun playSilentUtterance(durationMs: Long, utteranceId: String) {
        ttsChannel.trySend {
            withContext(Dispatchers.Main) { onTtsStart?.invoke(utteranceId) }
            delay(durationMs)
            if (isActive) {
                withContext(Dispatchers.Main) { onTtsDone?.invoke(utteranceId) }
            }
        }
    }

    override fun stopSpeaking() {
        ttsLoopJob?.cancel()
        ttsChannel.close()
        releaseSpeechDrainWaiters()
        ttsChannel = Channel(Channel.UNLIMITED)
        try {
            globalAudioTrack?.pause()
            globalAudioTrack?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush AudioTrack", e)
        }
        startTtsLoop()
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit,
    ) {
        onTtsStart = onStart
        onTtsDone = onDone
        onTtsError = onError
        onTtsRangeStart = onRangeStart
    }

    // ── Ear / STT ───────────────────────────────────────────────────────────

    override fun prewarmEar() {
        mainScope.launch(Dispatchers.IO) {
            val ok = ear.prewarm()
            Log.i(TAG, "prewarmEar ok=$ok state=${ear.currentState}")
        }
    }

    override fun startListening() {
        requestFocusAndMaxVolume()
        ear.startUtterance(force = false)
    }

    override fun startListeningForced() {
        requestFocusAndMaxVolume()
        ear.startUtterance(force = true)
    }

    override fun stopListening() {
        ear.stopUtterance()
        abandonFocus()
    }

    override fun destroySpeechRecognizer() {
        ear.close(releaseEngines = true)
        abandonFocus()
    }

    override fun setRecognitionListener(
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onEndOfSpeech: () -> Unit,
        onResult: (String) -> Unit,
        onEmptyResult: () -> Unit,
        onError: (Int) -> Unit,
        onPartial: (String) -> Unit,
    ) {
        ear.setCallbacks(
            EarSttCallbacks(
                onReadyForSpeech = onReadyForSpeech,
                onBeginningOfSpeech = onBeginningOfSpeech,
                onEndOfSpeech = onEndOfSpeech,
                onResult = onResult,
                onEmptyResult = onEmptyResult,
                onError = onError,
                onPartial = onPartial,
            ),
        )
    }

    override fun shutdown() {
        ear.shutdown()
        ttsLoopJob?.cancel()
        ttsChannel.close()
        releaseSpeechDrainWaiters()
        ttsScope.cancel()
        mainScope.cancel()
        try {
            globalAudioTrack?.pause()
            globalAudioTrack?.flush()
            globalAudioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release AudioTrack", e)
        }
        globalAudioTrack = null
        try {
            offlineTts?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release offline TTS", e)
        }
        offlineTts = null
        abandonFocus()
    }
}

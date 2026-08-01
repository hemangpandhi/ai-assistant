package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.tcs.vehicleassistant.LatencyLogger
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.TtsVoiceCatalog
import kotlinx.coroutines.*
import com.k2fsa.sherpa.onnx.*

class AndroidAudioManager(private val context: Context) : IAudioManager {

    private companion object {
        const val TAG = "AndroidAudioManager"
    }

    private var offlineTts: OfflineTts? = null
    @Volatile private var ttsSpeakerId: Int = 0
    @Volatile private var ttsSpeed: Float = 1.0f
    @Volatile private var loadedVoiceId: String = TtsVoiceCatalog.BUNDLED_AMY_ID

    // TTS callbacks
    private var onTtsStart: ((String) -> Unit)? = null
    private var onTtsDone: ((String) -> Unit)? = null
    private var onTtsError: ((String) -> Unit)? = null
    private var onTtsRangeStart: ((String, Int, Int, Int) -> Unit)? = null
    
    // STT callbacks (full lifecycle)
    private var onSttReadyForSpeech: (() -> Unit)? = null
    private var onSttBeginningOfSpeech: (() -> Unit)? = null
    private var onSttEndOfSpeech: (() -> Unit)? = null
    private var onSttResult: ((String) -> Unit)? = null
    private var onSttEmptyResult: (() -> Unit)? = null
    private var onSttError: ((Int) -> Unit)? = null
    private var onSttPartial: ((String) -> Unit)? = null

    private fun copyEspeakData(): String {
        val outDir = java.io.File(context.filesDir, "espeak-ng-data")
        if (outDir.exists() && java.io.File(outDir, "phontab").exists()) return outDir.absolutePath
        outDir.mkdirs()
        
        fun copyAssetsToDir(assetPath: String, destDir: java.io.File) {
            val list = context.assets.list(assetPath)
            if (list.isNullOrEmpty()) {
                try {
                    context.assets.open(assetPath).use { input ->
                        java.io.FileOutputStream(destDir).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch(e: Exception) {}
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
            lengthScale = 1.0f
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 1,
            debug = false,
            provider = "cpu"
        )
        val config = OfflineTtsConfig(
            model = modelConfig,
            ruleFsts = "",
            maxNumSentences = 1
        )
        loadedVoiceId = voice.id
        Log.i(TAG, "Loading TTS voice id=${voice.id} assets=${voice.fromAssets} catalogSpeakers=${voice.numSpeakers} speed=$ttsSpeed")
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
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize offline TTS", e)
            // Fall back to bundled Amy if a sideloaded voice is broken.
            try {
                val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(AssistantConfig.Prefs.TTS_VOICE_ID, TtsVoiceCatalog.BUNDLED_AMY_ID).apply()
                offlineTts = buildOfflineTts()
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

    /** Polled by the capture loop on an IO thread and written from the main thread. */
    @Volatile
    private var isListening = false
    private var sherpaRecognizer: OfflineRecognizer? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var acousticEchoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var listeningJob: Job? = null
    private var googleSpeechRecognizer: SpeechRecognizer? = null

    private fun initSpeechRecognizer() {
        if (sherpaRecognizer != null) return

        val sttDir = java.io.File(AssistantConfig.Audio.STT_SIDELOAD_DIR)
        // Prefer filesystem sideloads (adb push) over APK assets — avoids Git LFS / huge APK packaging.
        val baseEncoder = java.io.File(sttDir, "base.en-encoder.int8.onnx")
        val baseDecoder = java.io.File(sttDir, "base.en-decoder.int8.onnx")
        val baseTokens = java.io.File(sttDir, "base.en-tokens.txt")
        val tinyEncoder = java.io.File(sttDir, "tiny.en-encoder.int8.onnx")
        val tinyDecoder = java.io.File(sttDir, "tiny.en-decoder.int8.onnx")
        val tinyTokens = java.io.File(sttDir, "tiny.en-tokens.txt")
        val useBase = readableSideloadTriplet(baseEncoder, baseDecoder, baseTokens)
        val useTinySideload = readableSideloadTriplet(tinyEncoder, tinyDecoder, tinyTokens)

        try {
            when {
                useBase -> {
                    Log.i(TAG, "Using Whisper Base from ${sttDir.absolutePath}")
                    sherpaRecognizer = buildWhisperRecognizer(
                        encoderPath = baseEncoder.absolutePath,
                        decoderPath = baseDecoder.absolutePath,
                        tokensPath = baseTokens.absolutePath,
                    )
                }
                useTinySideload -> {
                    Log.i(TAG, "Using Whisper Tiny from ${sttDir.absolutePath}")
                    sherpaRecognizer = buildWhisperRecognizer(
                        encoderPath = tinyEncoder.absolutePath,
                        decoderPath = tinyDecoder.absolutePath,
                        tokensPath = tinyTokens.absolutePath,
                    )
                }
                else -> {
                    Log.e(
                        TAG,
                        "STT models missing under ${sttDir.absolutePath}. " +
                            "APK no longer packages Whisper; adb-push tiny or base " +
                            "(encoder/decoder/tokens) — see docs/MODEL_SIDELOAD.md",
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa-ONNX: ${e.message}", e)
        }
    }

    private fun readableSideloadTriplet(encoder: java.io.File, decoder: java.io.File, tokens: java.io.File): Boolean =
        listOf(encoder, decoder, tokens).all { it.exists() && it.canRead() && it.length() > 0L }

    /**
     * Builds an OfflineRecognizer for Whisper from absolute filesystem paths
     * (sherpa-onnx needs a null AssetManager; Whisper is no longer packaged in assets).
     */
    private fun buildWhisperRecognizer(
        encoderPath: String,
        decoderPath: String,
        tokensPath: String,
    ): OfflineRecognizer {
        val whisperConfig = OfflineWhisperModelConfig(
            encoder = encoderPath,
            decoder = decoderPath,
            language = "en",
            task = "transcribe",
            tailPaddings = -1,
        )
        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = tokensPath,
            numThreads = 4,
            debug = false,
            provider = "cpu",
            modelType = "whisper",
        )
        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val config = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig)
        // sherpa-onnx requires assetManager=null for absolute filesystem paths.
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        return OfflineRecognizer(null, config)
    }

    private var sherpaVad: Vad? = null

    private fun initVad() {
        if (sherpaVad != null) return
        try {
            val sileroConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = AssistantConfig.Audio.VAD_MIN_SILENCE_DURATION_SEC,
                minSpeechDuration = AssistantConfig.Audio.VAD_MIN_SPEECH_DURATION_SEC,
                windowSize = 512,
                maxSpeechDuration = 15.0f
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            sherpaVad = Vad(context.assets, vadConfig)
            android.util.Log.i("AndroidAudioManager", "Silero VAD initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("AndroidAudioManager", "Failed to init Silero VAD: ${e.message}", e)
        }
    }

    /**
     * Owned scope for the microphone capture loop. Previously each [startListening] created a
     * detached `CoroutineScope(Dispatchers.IO)`, so [shutdown] could not stop an in-flight capture.
     */
    private val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startListening() {
        if (isListening) return
        // Ensure any prior capture loop fully released AudioRecord before we open a new one.
        listeningJob?.cancel()
        listeningJob = null
        isListening = true

        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val sttEngine = prefs.getString(AssistantConfig.Prefs.STT_ENGINE, AssistantConfig.Prefs.STT_ENGINE_SHERPA)
        
        if (sttEngine == AssistantConfig.Prefs.STT_ENGINE_GOOGLE) {
            startGoogleSpeechRecognizer()
            return
        }

        listeningJob = listeningScope.launch {
            try {
                initSpeechRecognizer()
                initVad()
                if (sherpaRecognizer == null) {
                    withContext(Dispatchers.Main) { 
                        isListening = false
                        onSttError?.invoke(0)
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    onSttReadyForSpeech?.invoke()
                }
                
                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    AssistantConfig.Audio.SAMPLE_RATE_HZ,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                ) * 2

                // Retry loop covers the handoff window while the :wakeword process releases the mic.
                var audioRecordAttempts = 0
                while (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED &&
                    audioRecordAttempts < AssistantConfig.Audio.AUDIO_RECORD_MAX_ATTEMPTS
                ) {
                    if (audioRecordAttempts > 0) delay(AssistantConfig.Audio.AUDIO_RECORD_RETRY_DELAY_MS)
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        AssistantConfig.Audio.SAMPLE_RATE_HZ,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    audioRecordAttempts++
                }
                
                if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    android.util.Log.e("AndroidAudioManager", "Failed to initialize AudioRecord after $audioRecordAttempts attempts.")
                    withContext(Dispatchers.Main) {
                        isListening = false
                        onSttError?.invoke(0)
                    }
                    return@launch
                }

                audioRecord?.audioSessionId?.let { sessionId ->
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId)
                        noiseSuppressor?.enabled = true
                    }
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        acousticEchoCanceler = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                        acousticEchoCanceler?.enabled = true
                    }
                }
                
                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                
                var hasSpoken = false
                var silenceFrames = 0
                var silenceStartedAtMs = 0L
                var noSpeechStartedAtMs = 0L
                val listenStartedAtMs = System.currentTimeMillis()
                val audioBuffer = mutableListOf<Float>()
                sherpaVad?.reset()
                
                var noSpeechFrames = 0
                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val floatSamples = FloatArray(readSize) { buffer[it].toFloat() / 32768.0f }
                        for (f in floatSamples) audioBuffer.add(f)

                        // Feed float samples to Silero Neural VAD
                        val vad = sherpaVad
                        val now = System.currentTimeMillis()
                        if (vad != null) {
                            vad.acceptWaveform(floatSamples)
                            val isSpeech = vad.isSpeechDetected()
                            if (isSpeech) {
                                silenceFrames = 0
                                silenceStartedAtMs = 0L
                                noSpeechFrames = 0
                                noSpeechStartedAtMs = 0L
                                if (!hasSpoken) {
                                    hasSpoken = true
                                    LatencyLogger.log("STT", "Beginning of speech")
                                    withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                                }
                            } else {
                                if (hasSpoken) {
                                    silenceFrames++
                                    if (silenceStartedAtMs == 0L) silenceStartedAtMs = now
                                } else {
                                    noSpeechFrames++
                                    if (noSpeechStartedAtMs == 0L) noSpeechStartedAtMs = now
                                }
                            }
                        } else {
                            // Fallback to basic volume threshold if VAD init failed
                            var sumSquares = 0.0
                            for (i in 0 until readSize) {
                                val s = buffer[i].toDouble()
                                sumSquares += s * s
                            }
                            val rms = Math.sqrt(sumSquares / readSize)
                            if (rms > 200.0) {
                                silenceFrames = 0
                                silenceStartedAtMs = 0L
                                noSpeechFrames = 0
                                noSpeechStartedAtMs = 0L
                                if (!hasSpoken) {
                                    hasSpoken = true
                                    LatencyLogger.log("STT", "Beginning of speech")
                                    withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                                }
                            } else if (hasSpoken) {
                                silenceFrames++
                                if (silenceStartedAtMs == 0L) silenceStartedAtMs = now
                            } else {
                                noSpeechFrames++
                                if (noSpeechStartedAtMs == 0L) noSpeechStartedAtMs = now
                            }
                        }
                        
                        // Close the utterance when the VAD reports end-of-segment, when trailing
                        // silence exceeds the wall-clock budget, or when the user never spoke.
                        val isSegmentFinished = sherpaVad?.empty() == false
                        val trailingSilenceElapsed = silenceStartedAtMs > 0L &&
                            (now - silenceStartedAtMs) >= AssistantConfig.Audio.TRAILING_SILENCE_MS
                        val noSpeechTimeout = noSpeechStartedAtMs > 0L &&
                            (now - noSpeechStartedAtMs) >= AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS
                        if ((hasSpoken && (isSegmentFinished || trailingSilenceElapsed)) || noSpeechTimeout) {
                            val eosReason = when {
                                noSpeechTimeout -> "no_speech_timeout"
                                isSegmentFinished -> "vad_segment"
                                else -> "trailing_silence_ms"
                            }
                            LatencyLogger.log(
                                "STT",
                                "End of speech ($eosReason) listen=${now - listenStartedAtMs}ms",
                            )
                            withContext(Dispatchers.Main) {
                                onSttEndOfSpeech?.invoke()
                            }
                            
                            val stream = sherpaRecognizer?.createStream()
                            if (stream != null && hasSpoken) {
                                val decodeStarted = System.currentTimeMillis()
                                val floatArray = audioBuffer.toFloatArray()
                                stream.acceptWaveform(floatArray, 16000)
                                sherpaRecognizer?.decode(stream)
                                val result = sherpaRecognizer?.getResult(stream)?.text?.trim() ?: ""
                                stream.release()
                                LatencyLogger.log(
                                    "STT",
                                    "Transcript ready in ${System.currentTimeMillis() - decodeStarted}ms result='${result.take(48)}'",
                                )
                                android.util.Log.d("AndroidAudioManager", "STT result='$result'")
                                
                                withContext(Dispatchers.Main) {
                                    if (result.isNotBlank()) {
                                        val speaker = com.tcs.vehicleassistant.vision.VisionState.recognizedUser
                                        val taggedResult = "[Seat: $speaker] $result"
                                        onSttResult?.invoke(taggedResult)
                                    } else {
                                        onSttEmptyResult?.invoke()
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) { onSttEmptyResult?.invoke() }
                            }
                            isListening = false
                            break
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: CancellationException) {
                isListening = false
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Microphone capture failed", e)
                withContext(Dispatchers.Main) {
                    isListening = false
                    onSttError?.invoke(0)
                }
            } finally {
                releaseCaptureResources()
            }
        }
    }

    private fun startGoogleSpeechRecognizer() {
        CoroutineScope(Dispatchers.Main).launch {
            if (googleSpeechRecognizer == null) {
                googleSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            onSttReadyForSpeech?.invoke()
                        }
                        override fun onBeginningOfSpeech() {
                            LatencyLogger.log("STT", "Beginning of speech (Google)")
                            onSttBeginningOfSpeech?.invoke()
                        }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            LatencyLogger.log("STT", "End of speech (Google)")
                            onSttEndOfSpeech?.invoke()
                        }
                        override fun onError(error: Int) {
                            Log.e(TAG, "Google SpeechRecognizer error: $error")
                            isListening = false
                            onSttError?.invoke(error)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val bestResult = matches?.firstOrNull() ?: ""
                            if (bestResult.isNotBlank()) {
                                val speaker = com.tcs.vehicleassistant.vision.VisionState.recognizedUser
                                val taggedResult = "[Seat: $speaker] $bestResult"
                                onSttResult?.invoke(taggedResult)
                            } else {
                                onSttEmptyResult?.invoke()
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { onSttPartial?.invoke(it) }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            googleSpeechRecognizer?.startListening(intent)
        }
    }

    private fun releaseCaptureResources() {
        try {
            if (audioRecord?.state == android.media.AudioRecord.STATE_INITIALIZED) audioRecord?.stop()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to stop AudioRecord", e)
        }
        try { audioRecord?.release() } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release AudioRecord", e)
        }
        audioRecord = null
        try { noiseSuppressor?.release() } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release noise suppressor", e)
        }
        noiseSuppressor = null
        try { acousticEchoCanceler?.release() } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release echo canceler", e)
        }
        acousticEchoCanceler = null
    }

    override fun stopListening() {
        if (!isListening) return
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        CoroutineScope(Dispatchers.Main).launch {
            try {
                googleSpeechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop Google SpeechRecognizer", e)
            }
        }
    }

    override fun destroySpeechRecognizer() {
        stopListening()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                googleSpeechRecognizer?.destroy()
                googleSpeechRecognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy Google SpeechRecognizer", e)
            }
        }
        try {
            sherpaRecognizer?.release()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release Sherpa recognizer", e)
        }
        sherpaRecognizer = null
        try {
            sherpaVad?.release()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release Silero VAD", e)
        }
        sherpaVad = null
    }

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ttsChannel = kotlinx.coroutines.channels.Channel<suspend CoroutineScope.() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var ttsLoopJob: Job? = null
    private var globalAudioTrack: AudioTrack? = null

    /** Callers parked in [waitUntilFinishedSpeaking], so discarding the queue can release them. */
    private val speechDrainWaiters =
        java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.CompletableDeferred<Unit>>()

    init {
        startTtsLoop()
    }

    private fun startTtsLoop() {
        ttsLoopJob = ttsScope.launch {
            for (task in ttsChannel) {
                if (!isActive) break
                try {
                    task()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {}
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        ttsChannel.trySend {
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
                        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) { onTtsError?.invoke(utteranceId) }
                }
            }
        }
    }

    /**
     * Waits for queued speech to drain by putting a marker on the TTS queue.
     *
     * The wait is bounded because [stopSpeaking] and [shutdown] discard the queue: a barge-in while
     * a tool call was waiting here left the marker unrun and the caller suspended forever, wedging
     * the turn in `AgentOrchestrator`.
     */
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
                android.util.Log.w(TAG, "Speech queue did not drain in time; continuing.")
            }
        } finally {
            speechDrainWaiters.remove(deferred)
        }
    }

    /** Releases anyone blocked in [waitUntilFinishedSpeaking] when the queue is thrown away. */
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
        ttsChannel = kotlinx.coroutines.channels.Channel<suspend CoroutineScope.() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            globalAudioTrack?.pause()
            globalAudioTrack?.flush()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to flush AudioTrack", e)
        }
        startTtsLoop()
    }

    /**
     * Terminal teardown. This releases every native handle the manager owns and cancels its
     * scopes — the previous implementation left the [AudioTrack], the Silero VAD, and [ttsScope]
     * alive, so each service restart leaked an audio track and an ONNX session.
     */
    override fun shutdown() {
        isListening = false
        ttsLoopJob?.cancel()
        ttsChannel.close()
        releaseSpeechDrainWaiters()
        ttsScope.cancel()
        listeningScope.cancel()

        try {
            globalAudioTrack?.pause()
            globalAudioTrack?.flush()
            globalAudioTrack?.release()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release AudioTrack", e)
        }
        globalAudioTrack = null

        try {
            offlineTts?.release()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to release offline TTS", e)
        }
        offlineTts = null

        releaseCaptureResources()
        destroySpeechRecognizer()
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit
    ) {
        this.onTtsStart = onStart
        this.onTtsDone = onDone
        this.onTtsError = onError
        this.onTtsRangeStart = onRangeStart
    }

    override fun setRecognitionListener(
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onEndOfSpeech: () -> Unit,
        onResult: (String) -> Unit,
        onEmptyResult: () -> Unit,
        onError: (Int) -> Unit,
        onPartial: (String) -> Unit
    ) {
        this.onSttReadyForSpeech = onReadyForSpeech
        this.onSttBeginningOfSpeech = onBeginningOfSpeech
        this.onSttEndOfSpeech = onEndOfSpeech
        this.onSttResult = onResult
        this.onSttEmptyResult = onEmptyResult
        this.onSttError = onError
        this.onSttPartial = onPartial
    }
}

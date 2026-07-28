package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.*
import com.k2fsa.sherpa.onnx.*

class AndroidAudioManager(private val context: Context) : IAudioManager {

    private var offlineTts: OfflineTts? = null
    
    // TTS callbacks
    private var onTtsStart: ((String) -> Unit)? = null
    private var onTtsDone: ((String) -> Unit)? = null
    private var onTtsError: ((String) -> Unit)? = null
    private var onTtsRangeStart: ((String, Int, Int, Int) -> Unit)? = null

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

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
        try {
            val espeakDataPath = copyEspeakData()
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = "sherpa-onnx-tts/en_US-amy-low.onnx",
                tokens = "sherpa-onnx-tts/tokens.txt",
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
            offlineTts = OfflineTts(context.assets, config)
            onSuccess()
        } catch (e: Exception) {
            e.printStackTrace()
            onError()
        }
    }

    private var isListening = false
    private var sherpaRecognizer: OfflineRecognizer? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var acousticEchoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var listeningJob: Job? = null

    private fun initSpeechRecognizer() {
        if (sherpaRecognizer != null) return

        // Try Whisper Base (74M params, much more accurate) from device filesystem first
        val baseEncoder = java.io.File("/data/local/tmp/stt/base.en-encoder.int8.onnx")
        val baseDecoder = java.io.File("/data/local/tmp/stt/base.en-decoder.int8.onnx")
        val baseTokens = java.io.File("/data/local/tmp/stt/base.en-tokens.txt")
        val useBase = baseEncoder.exists() && baseDecoder.exists() && baseTokens.exists()

        try {
            if (useBase) {
                android.util.Log.i("AndroidAudioManager", "Using Whisper Base model (high accuracy)")
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = baseEncoder.absolutePath,
                    decoder = baseDecoder.absolutePath,
                    language = "en",
                    task = "transcribe",
                    tailPaddings = -1
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = baseTokens.absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper"
                )
                val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
                val config = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig)
                // sherpa-onnx requires assetManager=null for absolute filesystem paths.
                // Use the (AssetManager?, Config) constructor with null.
                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                sherpaRecognizer = OfflineRecognizer(null, config)
            } else {
                android.util.Log.w("AndroidAudioManager", "Whisper Base not found, falling back to Tiny from assets")
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = "sherpa-onnx-whisper/tiny.en-encoder.int8.onnx",
                    decoder = "sherpa-onnx-whisper/tiny.en-decoder.int8.onnx",
                    language = "en",
                    task = "transcribe",
                    tailPaddings = -1
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = "sherpa-onnx-whisper/tiny.en-tokens.txt",
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper"
                )
                val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
                val config = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig)
                sherpaRecognizer = OfflineRecognizer(context.assets, config)
            }
        } catch (e: Exception) {
            android.util.Log.e("AndroidAudioManager", "Failed to init Sherpa-ONNX: ${e.message}", e)
        }
    }

    private var sherpaVad: Vad? = null

    private fun initVad() {
        if (sherpaVad != null) return
        try {
            val sileroConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 1.0f,
                minSpeechDuration = 0.25f,
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

    override fun ensureWarmRecognizer() {
        val run = Runnable { ensureRecognizer() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun isActivelyListening(): Boolean =
        sttPhase == SttPhase.Starting || sttPhase == SttPhase.Listening

    override fun isReadyListening(): Boolean =
        sttPhase == SttPhase.Listening

    @Volatile
    private var endpointingProfile: EndpointingProfile = EndpointingProfile.Default

    override fun startListening() {
        if (isListening) return
        isListening = true
        
        listeningJob = CoroutineScope(Dispatchers.IO).launch {
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
                
                val bufferSize = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT) * 2
                audioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
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
                val audioBuffer = mutableListOf<Float>()
                sherpaVad?.reset()
                
                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val floatSamples = FloatArray(readSize) { buffer[it].toFloat() / 32768.0f }
                        for (f in floatSamples) audioBuffer.add(f)

                        // Feed float samples to Silero Neural VAD
                        val vad = sherpaVad
                        if (vad != null) {
                            vad.acceptWaveform(floatSamples)
                            val isSpeech = vad.isSpeechDetected()
                            if (isSpeech) {
                                silenceFrames = 0
                                if (!hasSpoken) {
                                    hasSpoken = true
                                    withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                                }
                            } else {
                                if (hasSpoken) silenceFrames++
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
                                if (!hasSpoken) {
                                    hasSpoken = true
                                    withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                                }
                            } else if (hasSpoken) {
                                silenceFrames++
                            }
                        }
                        
                        // Stop listening when Silero VAD detects end of speech segment or 1.0s silence reached
                        val isSegmentFinished = sherpaVad?.empty() == false
                        if (hasSpoken && (isSegmentFinished || silenceFrames > 10)) {
                            withContext(Dispatchers.Main) {
                                onSttEndOfSpeech?.invoke()
                            }
                            
                            val stream = sherpaRecognizer?.createStream()
                            if (stream != null) {
                                val floatArray = audioBuffer.toFloatArray()
                                stream.acceptWaveform(floatArray, 16000)
                                sherpaRecognizer?.decode(stream)
                                val result = sherpaRecognizer?.getResult(stream)?.text?.trim() ?: ""
                                stream.release()
                                android.util.Log.d("AndroidAudioManager", "STT result='$result'")
                                
                                withContext(Dispatchers.Main) {
                                    if (result.isNotBlank()) {
                                        onSttResult?.invoke(result)
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isListening = false
                    onSttError?.invoke(0)
                }
            } finally {
                try { audioRecord?.stop() } catch (e: Exception) {}
                try { audioRecord?.release() } catch (e: Exception) {}
                audioRecord = null
                try { noiseSuppressor?.release() } catch (e: Exception) {}
                noiseSuppressor = null
                try { acousticEchoCanceler?.release() } catch (e: Exception) {}
                acousticEchoCanceler = null
            }
        }
    }


    override fun stopListening() {
        if (!isListening) return
        isListening = false
        listeningJob?.cancel()
    }

    override fun destroySpeechRecognizer() {
        stopListening()
        sherpaRecognizer?.release()
        sherpaRecognizer = null
    }

    private val ttsScope = CoroutineScope(Dispatchers.IO)
    private var ttsChannel = kotlinx.coroutines.channels.Channel<suspend CoroutineScope.() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var ttsLoopJob: Job? = null
    private var globalAudioTrack: AudioTrack? = null

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
        if (holdingDuck) {
            requestAssistantDuckLocked()
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
                    val audio = offlineTts?.generate(sentence, sid = 0, speed = 1.0f) ?: continue
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

    override suspend fun waitUntilFinishedSpeaking() {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        val result = ttsChannel.trySend {
            delay(500)
            deferred.complete(Unit)
        }
        if (result.isSuccess) {
            deferred.await()
        }
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
        ttsChannel = kotlinx.coroutines.channels.Channel<suspend CoroutineScope.() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            globalAudioTrack?.pause()
            globalAudioTrack?.flush()
        } catch(e: Exception) {}
        startTtsLoop()
    }

    override fun shutdown() {
        stopSpeaking()
        offlineTts?.release()
        offlineTts = null

        destroySpeechRecognizer()
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit,
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
        onPartial: (String) -> Unit,
    ) {
        this.onSttReadyForSpeech = onReadyForSpeech
        this.onSttBeginningOfSpeech = onBeginningOfSpeech
        this.onSttEndOfSpeech = onEndOfSpeech
        this.onSttResult = onResult
        this.onSttEmptyResult = onEmptyResult
        this.onSttError = onError
        this.onSttPartial = onPartial
    }

    companion object {
        private const val POST_STOP_SETTLE_MS = 220L
        private const val DESTROY_SETTLE_MS = 400L
        private const val BUSY_BACKOFF_MS = 900L
        private const val CLIENT_BACKOFF_MS = 550L
        private const val MIN_START_GAP_MS = 450L
        private const val READY_WATCHDOG_MS = 2500L
        /** Window where ERROR_CLIENT from cancel/stop/destroy is ignored. */
        private const val IGNORE_CLIENT_ERROR_MS = 600L

        fun sttErrorLabel(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            else -> "ERROR_UNKNOWN"
        }
    }
}

package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sherpa-ONNX offline STT (Whisper tiny.en) + Piper VITS TTS stack from [dev/refactor],
 * implementing [SessionAudioPort] for UI/UX mic handoff / ducking / endpointing.
 */
class AndroidAudioManager(private val context: Context) : SessionAudioPort {

    companion object {
        /** @deprecated Prefer [SpeechRecognitionErrors.label]. */
        fun sttErrorLabel(error: Int): String = SpeechRecognitionErrors.label(error)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG = "AndroidAudioManager"

    private var offlineTts: OfflineTts? = null

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

    @Volatile private var isListening = false
    @Volatile private var hasSignaledReady = false
    @Volatile private var startPending = false
    private var pendingStartRunnable: Runnable? = null
    private var sherpaRecognizer: OfflineRecognizer? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var listeningJob: Job? = null
    private var endpointingProfile: EndpointingProfile = EndpointingProfile.Default

    @Volatile private var holdingDuck = false
    private var duckFocusRequest: AudioFocusRequest? = null
    private val duckFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "duck focus change=$change holding=$holdingDuck")
    }

    private fun copyEspeakData(): String {
        val outDir = java.io.File(appContext.filesDir, "espeak-ng-data")
        if (outDir.exists() && java.io.File(outDir, "phontab").exists()) return outDir.absolutePath
        outDir.mkdirs()

        fun copyAssetsToDir(assetPath: String, destDir: java.io.File) {
            val list = appContext.assets.list(assetPath)
            if (list.isNullOrEmpty()) {
                try {
                    appContext.assets.open(assetPath).use { input ->
                        java.io.FileOutputStream(destDir).use { output ->
                            input.copyTo(output)
                        }
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

    override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) {
        // Warm STT early — independent of TTS init.
        ensureWarmRecognizer()
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
                lengthScale = 1.0f,
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 1,
                debug = false,
                provider = "cpu",
            )
            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                maxNumSentences = 1,
            )
            offlineTts = OfflineTts(appContext.assets, config)
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa TTS", e)
            onError()
        }
    }

    private fun initSpeechRecognizer() {
        if (sherpaRecognizer != null) return
        try {
            val whisperConfig = OfflineWhisperModelConfig(
                encoder = "sherpa-onnx-whisper/tiny.en-encoder.int8.onnx",
                decoder = "sherpa-onnx-whisper/tiny.en-decoder.int8.onnx",
                language = "en",
                task = "transcribe",
                tailPaddings = -1,
            )
            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = "sherpa-onnx-whisper/tiny.en-tokens.txt",
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            )
            val featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80,
            )
            val config = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
            )
            sherpaRecognizer = OfflineRecognizer(appContext.assets, config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa-ONNX Whisper: ${e.message}", e)
        }
    }

    override fun ensureWarmRecognizer() {
        val run = Runnable { initSpeechRecognizer() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun isActivelyListening(): Boolean =
        isListening || startPending

    override fun isReadyListening(): Boolean =
        isListening && hasSignaledReady

    override fun setEndpointingProfile(profile: EndpointingProfile) {
        endpointingProfile = profile
    }

    private fun silenceLimitFrames(profile: EndpointingProfile): Int {
        // ~80ms per AudioRecord read iteration on typical devices.
        return ((profile.completeSilenceMs / 80L).toInt()).coerceIn(6, 30)
    }

    override fun startListening() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startListeningInternal()
        } else {
            startPending = true
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            val run = Runnable {
                pendingStartRunnable = null
                startListeningInternal()
            }
            pendingStartRunnable = run
            mainHandler.post(run)
        }
    }

    override fun restartListening(delayedMs: Long) {
        stopListening()
        startPending = true
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        val run = Runnable {
            pendingStartRunnable = null
            startListeningInternal()
        }
        pendingStartRunnable = run
        if (delayedMs <= 0L) {
            mainHandler.post(run)
        } else {
            mainHandler.postDelayed(run, delayedMs)
        }
    }

    private fun startListeningInternal() {
        if (isListening) {
            startPending = false
            return
        }
        isListening = true
        startPending = false
        hasSignaledReady = false
        requestAssistantDuck()

        listeningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                initSpeechRecognizer()
                if (sherpaRecognizer == null) {
                    withContext(Dispatchers.Main) {
                        isListening = false
                        hasSignaledReady = false
                        onSttError?.invoke(0)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    hasSignaledReady = true
                    onSttReadyForSpeech?.invoke()
                }

                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                ) * 2
                audioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )

                if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        isListening = false
                        hasSignaledReady = false
                        onSttError?.invoke(0)
                    }
                    return@launch
                }

                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)

                var hasSpoken = false
                var silenceFrames = 0
                val speechThreshold = 500
                val silenceLimit = silenceLimitFrames(endpointingProfile)
                val audioBuffer = mutableListOf<Float>()

                while (isListening && isActive) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val amp = kotlin.math.abs(buffer[i].toInt())
                            if (amp > maxAmp) maxAmp = amp
                        }

                        if (maxAmp > speechThreshold) {
                            silenceFrames = 0
                            if (!hasSpoken) {
                                hasSpoken = true
                                withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                            }
                        } else if (hasSpoken) {
                            silenceFrames++
                        }

                        if (hasSpoken) {
                            for (i in 0 until readSize) {
                                audioBuffer.add(buffer[i].toFloat() / 32768.0f)
                            }
                        }

                        if (hasSpoken && silenceFrames > silenceLimit) {
                            withContext(Dispatchers.Main) {
                                onSttEndOfSpeech?.invoke()
                            }

                            val stream = sherpaRecognizer?.createStream()
                            if (stream != null) {
                                val floatArray = audioBuffer.toFloatArray()
                                stream.acceptWaveform(floatArray, 16000)
                                sherpaRecognizer?.decode(stream)
                                val result = sherpaRecognizer?.getResult(stream)?.text ?: ""
                                stream.release()

                                withContext(Dispatchers.Main) {
                                    if (result.isNotBlank()) {
                                        onSttResult?.invoke(result.trim())
                                    } else {
                                        onSttEmptyResult?.invoke()
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) { onSttEmptyResult?.invoke() }
                            }
                            isListening = false
                            hasSignaledReady = false
                            break
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: CancellationException) {
                // soft stop
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa listening loop error", e)
                withContext(Dispatchers.Main) {
                    isListening = false
                    hasSignaledReady = false
                    onSttError?.invoke(0)
                }
            } finally {
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {
                }
                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                audioRecord = null
                isListening = false
            }
        }
    }

    override fun stopListening() {
        startPending = false
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        if (!isListening) return
        isListening = false
        hasSignaledReady = false
        listeningJob?.cancel()
        listeningJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    override fun destroySpeechRecognizer() {
        stopListening()
        try {
            sherpaRecognizer?.release()
        } catch (_: Exception) {
        }
        sherpaRecognizer = null
    }

    private val ttsScope = CoroutineScope(Dispatchers.IO)
    private var ttsChannel =
        kotlinx.coroutines.channels.Channel<suspend CoroutineScope.() -> Unit>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
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
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        ttsChannel.trySend {
            try {
                withContext(Dispatchers.Main) { onTtsStart?.invoke(utteranceId) }

                val audio = offlineTts?.generate(text, sid = 0, speed = 1.0f)
                if (audio != null && isActive) {
                    val samples = audio.samples
                    val sampleRate = audio.sampleRate

                    if (globalAudioTrack == null || globalAudioTrack!!.sampleRate != sampleRate) {
                        globalAudioTrack?.release()
                        val minBufferSize = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        // USAGE_MEDIA is audible on AAOS; USAGE_ASSISTANT often is not.
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        val audioFormat = AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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
                    // Approximate range-start for lip-sync / UI progress.
                    withContext(Dispatchers.Main) {
                        onTtsRangeStart?.invoke(utteranceId, 0, text.length, 0)
                    }
                    globalAudioTrack?.write(shortSamples, 0, shortSamples.size)
                }

                if (isActive) {
                    withContext(Dispatchers.Main) { onTtsDone?.invoke(utteranceId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed", e)
                if (isActive) {
                    withContext(Dispatchers.Main) { onTtsError?.invoke(utteranceId) }
                }
            }
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
        ttsChannel = kotlinx.coroutines.channels.Channel(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
        try {
            globalAudioTrack?.pause()
            globalAudioTrack?.flush()
        } catch (_: Exception) {
        }
        startTtsLoop()
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

    override fun requestAssistantDuck() {
        val run = Runnable { requestAssistantDuckLocked() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    override fun abandonAssistantDuck() {
        val run = Runnable { abandonAssistantDuckLocked() }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun requestAssistantDuckLocked() {
        if (holdingDuck) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(duckFocusListener)
                    .build()
                duckFocusRequest = req
                val result = am.requestAudioFocus(req)
                holdingDuck = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    duckFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                holdingDuck = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAssistantDuck failed", e)
        }
    }

    private fun abandonAssistantDuckLocked() {
        if (!holdingDuck) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                duckFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(duckFocusListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandonAssistantDuck failed", e)
        } finally {
            duckFocusRequest = null
            holdingDuck = false
        }
    }

    override fun shutdown() {
        stopSpeaking()
        abandonAssistantDuck()
        try {
            offlineTts?.release()
        } catch (_: Exception) {
        }
        offlineTts = null
        destroySpeechRecognizer()
        try {
            globalAudioTrack?.release()
        } catch (_: Exception) {
        }
        globalAudioTrack = null
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
}

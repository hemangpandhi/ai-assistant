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
    private var listeningJob: Job? = null

    private fun initSpeechRecognizer() {
        if (sherpaRecognizer != null) return
        try {
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
            val featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            )
            val config = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig
            )
            sherpaRecognizer = OfflineRecognizer(context.assets, config)
        } catch (e: Exception) {
            android.util.Log.e("AndroidAudioManager", "Failed to init Sherpa-ONNX: ${e.message}", e)
        }
    }

    override fun startListening() {
        if (isListening) return
        isListening = true
        
        listeningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                initSpeechRecognizer()
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
                
                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                
                var hasSpoken = false
                var silenceFrames = 0
                val SPEECH_THRESHOLD = 500
                val audioBuffer = mutableListOf<Float>()
                
                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val amp = Math.abs(buffer[i].toInt())
                            if (amp > maxAmp) maxAmp = amp
                        }
                        
                        if (maxAmp > SPEECH_THRESHOLD) {
                            silenceFrames = 0
                            if (!hasSpoken) {
                                hasSpoken = true
                                withContext(Dispatchers.Main) { onSttBeginningOfSpeech?.invoke() }
                            }
                        } else {
                            if (hasSpoken) silenceFrames++
                        }
                        
                        if (hasSpoken) {
                            for (i in 0 until readSize) {
                                audioBuffer.add(buffer[i].toFloat() / 32768.0f)
                            }
                        }
                        
                        // Auto-stop after ~1.6 seconds of silence
                        if (hasSpoken && silenceFrames > 20) {
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
                    
                    val shortSamples = ShortArray(samples.size)
                    for (i in samples.indices) {
                        var v = samples[i]
                        // Sherpa-ONNX returns floats in [-1.0, 1.0]. Convert to 16-bit PCM.
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

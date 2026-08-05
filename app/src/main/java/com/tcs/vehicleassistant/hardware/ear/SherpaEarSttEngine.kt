package com.tcs.vehicleassistant.hardware.ear

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.tcs.vehicleassistant.LatencyLogger
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.vision.VisionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * On-device Whisper (Sherpa-ONNX) + Silero VAD. Buffers PCM until endpoint, then decodes.
 * Engine stays loaded across utterances; only VAD/buffers reset per turn.
 */
class SherpaEarSttEngine(
    private val context: Context,
    private var callbacks: EarSttCallbacks = EarSttCallbacks(),
) : EarSttEngine {

    companion object {
        private const val TAG = "SherpaEarStt"
        const val ERROR_MODELS_MISSING = 0

        fun modelsPresent(): Boolean {
            val sttDir = File(AssistantConfig.Audio.STT_SIDELOAD_DIR)
            return readableTriplet(sttDir, "base") || readableTriplet(sttDir, "tiny")
        }

        private fun readableTriplet(sttDir: File, prefix: String): Boolean {
            val encoder = File(sttDir, "$prefix.en-encoder.int8.onnx")
            val decoder = File(sttDir, "$prefix.en-decoder.int8.onnx")
            val tokens = File(sttDir, "$prefix.en-tokens.txt")
            return listOf(encoder, decoder, tokens).all { it.exists() && it.canRead() && it.length() > 0L }
        }
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private val audioBuffer = ArrayList<Float>(16_000 * 8)
    private var hasSpoken = false
    private var silenceStartedAtMs = 0L
    private var noSpeechStartedAtMs = 0L
    private var listenStartedAtMs = 0L
    private var utteranceActive = false
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)

    fun setCallbacks(callbacks: EarSttCallbacks) {
        this.callbacks = callbacks
    }

    override fun isReady(): Boolean = recognizer != null

    override fun prepare(): Boolean {
        if (recognizer != null && vad != null) return true
        initRecognizer()
        initVad()
        return recognizer != null
    }

    override fun startUtterance() {
        utteranceActive = true
        hasSpoken = false
        silenceStartedAtMs = 0L
        noSpeechStartedAtMs = 0L
        listenStartedAtMs = System.currentTimeMillis()
        audioBuffer.clear()
        try {
            vad?.reset()
        } catch (_: Exception) {
        }
        mainScope.launch { callbacks.onReadyForSpeech() }
    }

    /**
     * @return true when the utterance should end (endpoint or no-speech timeout)
     */
    fun acceptPcmAndShouldEndpoint(frame: FloatArray, sampleCount: Int): Boolean {
        if (!utteranceActive || sampleCount <= 0) return false
        for (i in 0 until sampleCount) {
            audioBuffer.add(frame[i])
        }

        val now = System.currentTimeMillis()
        val localVad = vad
        if (localVad != null) {
            val slice = if (sampleCount == frame.size) frame else frame.copyOf(sampleCount)
            localVad.acceptWaveform(slice)
            if (localVad.isSpeechDetected()) {
                silenceStartedAtMs = 0L
                noSpeechStartedAtMs = 0L
                if (!hasSpoken) {
                    hasSpoken = true
                    LatencyLogger.log("STT", "Beginning of speech")
                    mainScope.launch { callbacks.onBeginningOfSpeech() }
                }
            } else if (hasSpoken) {
                if (silenceStartedAtMs == 0L) silenceStartedAtMs = now
            } else {
                if (noSpeechStartedAtMs == 0L) noSpeechStartedAtMs = now
            }
        } else {
            // RMS fallback when Silero failed to load
            var sumSquares = 0.0
            for (i in 0 until sampleCount) {
                val s = frame[i].toDouble() * 32768.0
                sumSquares += s * s
            }
            val rms = kotlin.math.sqrt(sumSquares / sampleCount)
            if (rms > 200.0) {
                silenceStartedAtMs = 0L
                noSpeechStartedAtMs = 0L
                if (!hasSpoken) {
                    hasSpoken = true
                    LatencyLogger.log("STT", "Beginning of speech")
                    mainScope.launch { callbacks.onBeginningOfSpeech() }
                }
            } else if (hasSpoken) {
                if (silenceStartedAtMs == 0L) silenceStartedAtMs = now
            } else {
                if (noSpeechStartedAtMs == 0L) noSpeechStartedAtMs = now
            }
        }

        val segmentFinished = vad?.empty() == false
        val trailingSilence = silenceStartedAtMs > 0L &&
            (now - silenceStartedAtMs) >= AssistantConfig.Audio.TRAILING_SILENCE_MS
        val noSpeechTimeout = noSpeechStartedAtMs > 0L &&
            (now - noSpeechStartedAtMs) >= AssistantConfig.Audio.NO_SPEECH_TIMEOUT_MS
        return (hasSpoken && (segmentFinished || trailingSilence)) || noSpeechTimeout
    }

    override fun onPcm(frame: FloatArray) {
        // Prefer acceptPcmAndShouldEndpoint from the capture loop.
        acceptPcmAndShouldEndpoint(frame, frame.size)
    }

    /**
     * Endpoint: decode buffered PCM and emit result/empty on the main thread.
     * Runs synchronously on the caller (IO) so the ear can re-arm after decode.
     */
    fun finishUtteranceBlocking() {
        if (!utteranceActive) return
        utteranceActive = false
        val spoken = hasSpoken
        val bufferCopy = audioBuffer.toFloatArray()
        audioBuffer.clear()

        val eosReason = when {
            !spoken -> "no_speech_timeout"
            else -> "endpoint"
        }
        LatencyLogger.log(
            "STT",
            "End of speech ($eosReason) listen=${System.currentTimeMillis() - listenStartedAtMs}ms",
        )
        mainScope.launch { callbacks.onEndOfSpeech() }

        if (!spoken || recognizer == null) {
            mainScope.launch { callbacks.onEmptyResult() }
            return
        }
        try {
            val stream = recognizer?.createStream() ?: run {
                mainScope.launch { callbacks.onEmptyResult() }
                return
            }
            val decodeStarted = System.currentTimeMillis()
            stream.acceptWaveform(bufferCopy, AssistantConfig.Audio.SAMPLE_RATE_HZ)
            recognizer?.decode(stream)
            val result = recognizer?.getResult(stream)?.text?.trim().orEmpty()
            stream.release()
            LatencyLogger.log(
                "STT",
                "Transcript ready in ${System.currentTimeMillis() - decodeStarted}ms " +
                    "result='${result.take(48)}'",
            )
            mainScope.launch {
                if (result.isNotBlank()) {
                    val speaker = VisionState.recognizedUser
                    callbacks.onResult("[Seat: $speaker] $result")
                } else {
                    callbacks.onEmptyResult()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            mainScope.launch { callbacks.onError(ERROR_MODELS_MISSING) }
        }
    }

    override fun endUtterance() {
        finishUtteranceBlocking()
    }

    override fun release() {
        utteranceActive = false
        audioBuffer.clear()
        try {
            recognizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release Sherpa", e)
        }
        recognizer = null
        try {
            vad?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release VAD", e)
        }
        vad = null
    }

    private fun initRecognizer() {
        if (recognizer != null) return
        val sttDir = File(AssistantConfig.Audio.STT_SIDELOAD_DIR)
        val baseEncoder = File(sttDir, "base.en-encoder.int8.onnx")
        val baseDecoder = File(sttDir, "base.en-decoder.int8.onnx")
        val baseTokens = File(sttDir, "base.en-tokens.txt")
        val tinyEncoder = File(sttDir, "tiny.en-encoder.int8.onnx")
        val tinyDecoder = File(sttDir, "tiny.en-decoder.int8.onnx")
        val tinyTokens = File(sttDir, "tiny.en-tokens.txt")
        try {
            when {
                readableTriplet(sttDir, "base") -> {
                    Log.i(TAG, "Using Whisper Base from ${sttDir.absolutePath}")
                    recognizer = buildWhisper(
                        baseEncoder.absolutePath,
                        baseDecoder.absolutePath,
                        baseTokens.absolutePath,
                    )
                }
                readableTriplet(sttDir, "tiny") -> {
                    Log.i(TAG, "Using Whisper Tiny from ${sttDir.absolutePath}")
                    recognizer = buildWhisper(
                        tinyEncoder.absolutePath,
                        tinyDecoder.absolutePath,
                        tinyTokens.absolutePath,
                    )
                }
                else -> {
                    Log.e(
                        TAG,
                        "STT models missing under ${sttDir.absolutePath}. " +
                            "adb-push tiny or base — see docs/MODEL_SIDELOAD.md",
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa-ONNX: ${e.message}", e)
            recognizer = null
        }
    }

    private fun buildWhisper(
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
        val featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80)
        val config = OfflineRecognizerConfig(featConfig = featConfig, modelConfig = modelConfig)
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        return OfflineRecognizer(null, config)
    }

    private fun initVad() {
        if (vad != null) return
        try {
            val sileroConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = AssistantConfig.Audio.VAD_MIN_SILENCE_DURATION_SEC,
                minSpeechDuration = AssistantConfig.Audio.VAD_MIN_SPEECH_DURATION_SEC,
                windowSize = 512,
                maxSpeechDuration = 15.0f,
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = 16_000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = Vad(context.assets, vadConfig)
            Log.i(TAG, "Silero VAD initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Silero VAD: ${e.message}", e)
        }
    }
}

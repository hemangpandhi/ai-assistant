package com.tcs.vehicleassistant.hardware.ear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.tcs.vehicleassistant.core.AssistantConfig
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single Continuous Audio Pipeline that owns the HAL microphone.
 * Runs a continuous 20ms read loop.
 * - When `!isAwake`, pushes PCM to the ring buffer (for pre-roll) and feeds KWS.
 * - When `isAwake`, feeds the registered subscriber (STT engine).
 */
object ContinuousAudioPipeline {
    private const val TAG = "ContinuousAudioPipeline"
    const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz mono

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private val shortBuffer = ShortArray(FRAME_SAMPLES)

    val ringBuffer = PcmRingBuffer(24_000) // ~1.5s
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    
    private val _isRecording = AtomicBoolean(false)
    val isRecording: Boolean get() = _isRecording.get()
    
    @Volatile
    var isAwake = false
    
    // Callback for when STT is active
    @Volatile
    var sttSubscriber: ((FloatArray, Int) -> Unit)? = null
    
    // Callback for KWS (and Vosk fallback)
    @Volatile
    var kwsSubscriber: ((FloatArray, ShortArray, Int) -> Unit)? = null

    fun hasRecordAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        if (_isRecording.getAndSet(true)) return
        
        if (!hasRecordAudioPermission(context)) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot start pipeline")
            _isRecording.set(false)
            return
        }

        readJob = scope.launch {
            runReadLoop()
        }
    }
    
    fun stop() {
        if (!_isRecording.getAndSet(false)) return
        readJob?.cancel()
        readJob = null
        releaseEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    private suspend fun CoroutineScope.runReadLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            AssistantConfig.Audio.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            _isRecording.set(false)
            return
        }
        val bufferSize = maxOf(minBuf * 2, FRAME_SAMPLES * 4 * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AssistantConfig.Audio.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )

            val record = audioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return
            }

            try {
                val sessionId = record.audioSessionId
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler =
                        AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach NS/AEC", e)
            }

            record.startRecording()
            Log.i(TAG, "Continuous Audio Pipeline started (buffer=$bufferSize frame=$FRAME_SAMPLES)")

            val floatFrame = FloatArray(FRAME_SAMPLES)

            while (isActive && _isRecording.get()) {
                val read = record.read(shortBuffer, 0, FRAME_SAMPLES)
                if (read > 0) {
                    if (isAwake) {
                        // Feed STT
                        for (i in 0 until read) {
                            floatFrame[i] = shortBuffer[i].toFloat() / 32768.0f
                        }
                        sttSubscriber?.invoke(floatFrame, read)
                    } else {
                        // Feed Ring Buffer and KWS
                        for (i in 0 until read) {
                            floatFrame[i] = shortBuffer[i].toFloat() / 32768.0f
                        }
                        ringBuffer.push(floatFrame, read)
                        kwsSubscriber?.invoke(floatFrame, shortBuffer, read)
                    }
                } else {
                    delay(5)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline read loop failed", e)
        } finally {
            _isRecording.set(false)
            releaseEffects()
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up AudioRecord", e)
            }
            audioRecord = null
        }
    }

    private fun releaseEffects() {
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {}
        acousticEchoCanceler = null
    }
}

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
import kotlinx.coroutines.delay

/**
 * Session-owned microphone: allocated once in standby, [startRecording] / [stopRecording]
 * per utterance, released only on [release].
 *
 * Reads fixed ~20 ms frames (320 samples @ 16 kHz) for Silero-friendly chunking.
 */
class EarMic(
    private val context: Context,
) {
    companion object {
        private const val TAG = "EarMic"

        /** 20 ms @ 16 kHz mono. */
        const val FRAME_SAMPLES = 320

        fun hasRecordAudioPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
    }

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var shortBuffer: ShortArray = ShortArray(FRAME_SAMPLES)

    val isAllocated: Boolean
        get() = audioRecord?.state == AudioRecord.STATE_INITIALIZED

    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /**
     * Allocate [AudioRecord] in an unstarted standby state. Safe to call repeatedly;
     * no-ops when already allocated.
     *
     * @return true when a usable record handle exists
     */
    suspend fun allocateStandby(): Boolean {
        if (!hasRecordAudioPermission(context)) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot allocate mic")
            return false
        }
        if (isAllocated) return true

        val minBuf = AudioRecord.getMinBufferSize(
            AssistantConfig.Audio.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        // Internal buffer at least 4 frames so 20 ms reads never underrun the HAL ring.
        val bufferSize = maxOf(minBuf * 2, FRAME_SAMPLES * 4 * 2)

        var attempts = 0
        while (!isAllocated && attempts < AssistantConfig.Audio.AUDIO_RECORD_MAX_ATTEMPTS) {
            if (attempts > 0) delay(AssistantConfig.Audio.AUDIO_RECORD_RETRY_DELAY_MS)
            releaseEffects()
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AssistantConfig.Audio.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            attempts++
        }

        val record = audioRecord
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed after $attempts attempts")
            release()
            return false
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

        Log.i(TAG, "Standby AudioRecord ready (buffer=$bufferSize frame=$FRAME_SAMPLES)")
        return true
    }

    fun startRecording(): Boolean {
        val record = audioRecord ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) return false
        return try {
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.startRecording()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            false
        }
    }

    fun stopRecording() {
        val record = audioRecord ?: return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopRecording failed", e)
        }
    }

    /**
     * Blocking read of one 20 ms frame into [out] (float −1..1). Returns samples read,
     * or ≤0 on error / not recording.
     */
    fun readFrame(out: FloatArray): Int {
        require(out.size >= FRAME_SAMPLES) { "out must hold $FRAME_SAMPLES samples" }
        val record = audioRecord ?: return -1
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return -1
        val read = record.read(shortBuffer, 0, FRAME_SAMPLES)
        if (read <= 0) return read
        for (i in 0 until read) {
            out[i] = shortBuffer[i].toFloat() / 32768.0f
        }
        return read
    }

    /** Release HAL mic so another owner (e.g. Google SpeechRecognizer) can capture. */
    fun release() {
        stopRecording()
        releaseEffects()
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release failed", e)
        }
        audioRecord = null
    }

    private fun releaseEffects() {
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {
        }
        acousticEchoCanceler = null
    }
}

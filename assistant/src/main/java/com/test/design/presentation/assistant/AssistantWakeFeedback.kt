package com.test.design.presentation.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.concurrent.thread

/**
 * Soft haptics + sonification for assistant presence (slide up / slide down).
 *
 * Entry uses a struck **bell**; exit uses a soft settle tone. Both play via
 * [AudioTrack] with [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION] (not
 * [android.media.ToneGenerator], which destabilizes AAOS AVDs).
 */
class AssistantWakeFeedback(
    private val context: Context,
    private val composeHaptic: HapticFeedback?,
) {
    fun play() {
        playHaptic(confirm = true)
        playChime(entry = true)
    }

    fun playDismiss() {
        playHaptic(confirm = false)
        playChime(entry = false)
    }

    private fun playHaptic(confirm: Boolean) {
        try {
            composeHaptic?.performHapticFeedback(
                if (confirm) HapticFeedbackType.Confirm else HapticFeedbackType.LongPress,
            )
        } catch (_: Exception) {
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val ms = if (confirm) 36L else 24L
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Sonification earcons via [AudioTrack].
     * Entry: single struck bell (inharmonic partials + long ring).
     * Exit: soft falling settle tone (unchanged motif family, quieter).
     */
    private fun playChime(entry: Boolean) {
        thread(name = "assistant-chime", isDaemon = true) {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44_100
                val n: Int
                val pcm: ShortArray
                val master: Double
                if (entry) {
                    // Classic handbell / tubular-bell strike: bright attack, long metallic ring.
                    val durationMs = 1_100
                    n = sampleRate * durationMs / 1_000
                    pcm = ShortArray(n)
                    master = 0.22
                    // Inharmonic bell partial ratios (approximate physical bell spectrum).
                    val partials = arrayOf(
                        // amp, ratio, decay
                        doubleArrayOf(1.00, 0.50, 1.6),
                        doubleArrayOf(0.85, 1.00, 2.0),
                        doubleArrayOf(0.55, 1.20, 2.8),
                        doubleArrayOf(0.42, 1.50, 3.4),
                        doubleArrayOf(0.28, 2.00, 4.2),
                        doubleArrayOf(0.18, 2.50, 5.5),
                        doubleArrayOf(0.12, 3.00, 7.0),
                        doubleArrayOf(0.07, 4.20, 9.0),
                    )
                    val fundamentalHz = 784.0 // G5 — clear, recognizable bell pitch
                    for (i in 0 until n) {
                        val t = i.toDouble() / sampleRate
                        val sample = bellTone(t = t, fundamentalHz = fundamentalHz, partials = partials)
                        pcm[i] = (sample * master * Short.MAX_VALUE)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }
                } else {
                    // Soft falling settle (dismiss) — keep distinct from the entry bell.
                    val notesHz = doubleArrayOf(440.00, 329.63)
                    val strikesAt = doubleArrayOf(0.00, 0.20)
                    val noteGains = doubleArrayOf(1.00, 0.88)
                    val durationMs = 620
                    n = sampleRate * durationMs / 1_000
                    pcm = ShortArray(n)
                    master = 0.18
                    val partials = arrayOf(
                        doubleArrayOf(1.00, 1.0, 2.4),
                        doubleArrayOf(0.28, 2.0, 4.0),
                        doubleArrayOf(0.08, 3.0, 6.5),
                    )
                    for (i in 0 until n) {
                        val t = i.toDouble() / sampleRate
                        var sample = 0.0
                        for (ni in notesHz.indices) {
                            sample += softTone(
                                t = t,
                                strikeAt = strikesAt[ni],
                                fundamentalHz = notesHz[ni],
                                partials = partials,
                            ) * noteGains[ni]
                        }
                        pcm[i] = (sample * master * Short.MAX_VALUE)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }
                }

                val durationMs = if (entry) 1_100 else 620
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build()
                track.setVolume(if (entry) 0.62f else 0.50f)
                track.write(pcm, 0, n)
                track.play()
                Thread.sleep((durationMs + 80).toLong())
            } catch (_: Exception) {
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                try {
                    track?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Struck bell: fast metallic attack, inharmonic partials, long decaying ring + slight shimmer.
     */
    private fun bellTone(
        t: Double,
        fundamentalHz: Double,
        partials: Array<DoubleArray>,
    ): Double {
        if (t < 0.0) return 0.0
        // Sharp ~4 ms strike — reads as a bell hit, not a soft pad.
        val attack = if (t < 0.004) t / 0.004 else 1.0
        var sum = 0.0
        for (p in partials) {
            val amp = p[0]
            val ratio = p[1]
            val decay = p[2]
            val env = attack * exp(-decay * t)
            // Tiny beating on upper partials for metallic shimmer.
            val beat = if (ratio >= 2.0) 1.0 + 0.04 * sin(2.0 * PI * 3.5 * t) else 1.0
            sum += amp * env * beat * sin(2.0 * PI * fundamentalHz * ratio * t)
        }
        return sum
    }

    /** Soft struck tone: gentle attack, mellow harmonic ring (dismiss). */
    private fun softTone(
        t: Double,
        strikeAt: Double,
        fundamentalHz: Double,
        partials: Array<DoubleArray>,
    ): Double {
        val age = t - strikeAt
        if (age < 0.0) return 0.0
        val attack = if (age < 0.012) age / 0.012 else 1.0
        var sum = 0.0
        for (p in partials) {
            val amp = p[0]
            val ratio = p[1]
            val decay = p[2]
            val env = attack * exp(-decay * age)
            sum += amp * env * sin(2.0 * PI * fundamentalHz * ratio * t)
        }
        return sum
    }
}

@Composable
fun rememberAssistantWakeFeedback(): AssistantWakeFeedback {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return remember(context, haptics) {
        AssistantWakeFeedback(context, haptics)
    }
}

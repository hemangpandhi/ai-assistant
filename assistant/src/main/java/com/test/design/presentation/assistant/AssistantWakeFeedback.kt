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
 * Soft haptics + flat melodic earcons for assistant presence (slide up / slide down).
 *
 * Entry and exit use distinct motifs via [AudioTrack] with
 * [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION] (not [android.media.ToneGenerator],
 * which opens AUDIO_OUTPUT_FLAG_FAST tracks that destabilize AAOS AVDs).
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
     * Flat melodic earcons — soft harmonic partials (kalimba-like), not a clang.
     * Entry and exit use different motifs so summon ≠ dismiss.
     */
    private fun playChime(entry: Boolean) {
        thread(name = "assistant-chime", isDaemon = true) {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44_100
                // Entry: rising G4–B4–D5 major (welcome). Exit: falling A4–E4 fifth (settle).
                val notesHz: DoubleArray
                val strikesAt: DoubleArray
                val noteGains: DoubleArray
                val durationMs: Int
                if (entry) {
                    notesHz = doubleArrayOf(392.00, 493.88, 587.33)
                    strikesAt = doubleArrayOf(0.00, 0.14, 0.28)
                    noteGains = doubleArrayOf(1.00, 0.90, 0.82)
                    durationMs = 680
                } else {
                    notesHz = doubleArrayOf(440.00, 329.63)
                    strikesAt = doubleArrayOf(0.00, 0.20)
                    noteGains = doubleArrayOf(1.00, 0.88)
                    durationMs = 620
                }
                // Flat / mellow: fundamental-led harmonics, highs heavily damped.
                val partials = arrayOf(
                    doubleArrayOf(1.00, 1.0, 2.4),
                    doubleArrayOf(0.28, 2.0, 4.0),
                    doubleArrayOf(0.08, 3.0, 6.5),
                )
                val n = sampleRate * durationMs / 1_000
                val pcm = ShortArray(n)
                val master = 0.20
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
                track.setVolume(0.50f)
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

    /** Soft struck tone: gentle attack, mellow harmonic ring. */
    private fun softTone(
        t: Double,
        strikeAt: Double,
        fundamentalHz: Double,
        partials: Array<DoubleArray>,
    ): Double {
        val age = t - strikeAt
        if (age < 0.0) return 0.0
        // Softer ~12 ms attack — flatter, less percussive clang.
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

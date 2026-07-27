package com.assistant.ui.assistant.audio

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
 * Entry uses a **soft bell**; exit uses a quiet settle tone. Both play via
 * [AudioTrack] with [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION].
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
            val ms = if (confirm) 28L else 20L
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

    private fun playChime(entry: Boolean) {
        thread(name = "assistant-chime", isDaemon = true) {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44_100
                val durationMs: Int
                val n: Int
                val pcm: ShortArray
                if (entry) {
                    // Soft desk-bell: gentle attack, few partials, quiet warm ring.
                    durationMs = 900
                    n = sampleRate * durationMs / 1_000
                    pcm = ShortArray(n)
                    val master = 0.11
                    val partials = arrayOf(
                        // amp, ratio, decay — fundamental-led, highs heavily damped
                        doubleArrayOf(1.00, 1.00, 2.8),
                        doubleArrayOf(0.35, 2.00, 5.5),
                        doubleArrayOf(0.12, 3.01, 8.0),
                        doubleArrayOf(0.05, 4.20, 11.0),
                    )
                    val fundamentalHz = 523.25 // C5 — softer than bright G5
                    for (i in 0 until n) {
                        val t = i.toDouble() / sampleRate
                        val sample = softBellTone(t, fundamentalHz, partials)
                        pcm[i] = (sample * master * Short.MAX_VALUE)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }
                } else {
                    durationMs = 520
                    n = sampleRate * durationMs / 1_000
                    pcm = ShortArray(n)
                    val master = 0.10
                    val notesHz = doubleArrayOf(392.00, 293.66)
                    val strikesAt = doubleArrayOf(0.00, 0.18)
                    val noteGains = doubleArrayOf(1.00, 0.75)
                    val partials = arrayOf(
                        doubleArrayOf(1.00, 1.0, 3.0),
                        doubleArrayOf(0.22, 2.0, 5.5),
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
                track.setVolume(if (entry) 0.38f else 0.32f)
                track.write(pcm, 0, n)
                track.play()
                Thread.sleep((durationMs + 60).toLong())
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

    /** Soft bell: rounded attack, warm fundamental, muted overtones. */
    private fun softBellTone(
        t: Double,
        fundamentalHz: Double,
        partials: Array<DoubleArray>,
    ): Double {
        if (t < 0.0) return 0.0
        // ~18 ms soft attack — no metallic clang.
        val attack = if (t < 0.018) t / 0.018 else 1.0
        var sum = 0.0
        for (p in partials) {
            val amp = p[0]
            val ratio = p[1]
            val decay = p[2]
            val env = attack * exp(-decay * t)
            sum += amp * env * sin(2.0 * PI * fundamentalHz * ratio * t)
        }
        return sum
    }

    private fun softTone(
        t: Double,
        strikeAt: Double,
        fundamentalHz: Double,
        partials: Array<DoubleArray>,
    ): Double {
        val age = t - strikeAt
        if (age < 0.0) return 0.0
        val attack = if (age < 0.014) age / 0.014 else 1.0
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

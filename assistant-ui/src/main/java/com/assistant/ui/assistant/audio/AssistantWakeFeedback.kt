package com.assistant.ui.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
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
 * Entry uses a short rising melodic motif; exit uses a quiet settle tone.
 *
 * Playback uses [AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE] — the same
 * volume group the session duck holds — so AAOS does not attenuate the chime.
 * [AudioAttributes.USAGE_MEDIA] is ducked with background music; SONIFICATION is
 * often silent on car audio builds.
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
            var focusRequest: AudioFocusRequest? = null
            val am = try {
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            } catch (_: Exception) {
                null
            }
            val focusListener = AudioManager.OnAudioFocusChangeListener { }
            // Match session duck usage so the earcon rides the unducked nav volume group.
            val chimeAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            try {
                if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(chimeAttrs)
                        .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
                        .setWillPauseWhenDucked(false)
                        .build()
                    focusRequest = req
                    val focusResult = am.requestAudioFocus(req)
                    Log.d(TAG, "chime focus result=$focusResult entry=$entry")
                } else if (am != null) {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(
                        focusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                    )
                }

                val sampleRate = 44_100
                val durationMs: Int
                val n: Int
                val pcm: ShortArray
                if (entry) {
                    // Rising C–E–G major arpeggio — melodic welcome.
                    durationMs = 820
                    n = sampleRate * durationMs / 1_000
                    pcm = ShortArray(n)
                    val master = 0.32
                    val notesHz = doubleArrayOf(523.25, 659.25, 783.99) // C5–E5–G5
                    val strikesAt = doubleArrayOf(0.00, 0.13, 0.26)
                    val noteGains = doubleArrayOf(1.00, 0.92, 0.84)
                    val partials = arrayOf(
                        doubleArrayOf(1.00, 1.00, 2.6),
                        doubleArrayOf(0.30, 2.00, 4.8),
                        doubleArrayOf(0.10, 3.01, 7.5),
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
                } else {
                    durationMs = 520
                    n = sampleRate * durationMs / 1_000
                    pcm = ShortArray(n)
                    val master = 0.22
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

                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(1)
                // MODE_STREAM is more reliable on AAOS than MODE_STATIC for short PCM.
                val bufferBytes = maxOf(minBuf, 4_096)
                track = AudioTrack.Builder()
                    .setAudioAttributes(chimeAttrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioTrack not initialized state=${track.state}")
                    return@thread
                }
                track.setVolume(if (entry) 1.0f else 0.75f)
                track.play()
                var offset = 0
                while (offset < n) {
                    val written = track.write(pcm, offset, n - offset)
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack write failed result=$written at offset=$offset")
                        break
                    }
                    if (written == 0) {
                        Thread.sleep(4)
                        continue
                    }
                    offset += written
                }
                // Let the last buffer drain.
                Thread.sleep((durationMs + 80).toLong())
                Log.d(TAG, "chime played entry=$entry samples=$n written=$offset")
            } catch (t: Exception) {
                Log.w(TAG, "chime failed entry=$entry: ${t.message}", t)
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                try {
                    track?.release()
                } catch (_: Exception) {
                }
                // Release only this chime's brief focus; the session keeps its own duck.
                try {
                    if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        focusRequest?.let { am.abandonAudioFocusRequest(it) }
                    } else if (am != null) {
                        @Suppress("DEPRECATION")
                        am.abandonAudioFocus(focusListener)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Soft struck tone: gentle attack, warm harmonic ring (melodic earcons). */
    private fun softTone(
        t: Double,
        strikeAt: Double,
        fundamentalHz: Double,
        partials: Array<DoubleArray>,
    ): Double {
        val age = t - strikeAt
        if (age < 0.0) return 0.0
        // ~12 ms soft attack — rounded pluck, not a clang.
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

    companion object {
        private const val TAG = "AssistantWakeChime"
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

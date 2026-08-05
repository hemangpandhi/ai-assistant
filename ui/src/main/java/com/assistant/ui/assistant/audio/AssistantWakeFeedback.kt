package com.assistant.ui.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
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
import com.tcs.vehicleassistant.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Soft haptics + sonification for assistant presence (slide up / slide down).
 *
 * Entry plays [R.raw.intro]; exit plays [R.raw.exit].
 *
 * Playback uses [AudioAttributes.USAGE_MEDIA] (same as [AssistantTts]) — on AAOS,
 * [AudioAttributes.USAGE_ASSISTANT] is often silent / not routed without a car
 * audio patch, so earcons would not follow media/"system" volume. No separate
 * focus request: the session's exclusive hold ([AssistantSessionAudioFocus]) keeps
 * other media paused for the overlay lifetime.
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
            var player: MediaPlayer? = null
            val appContext = context.applicationContext
            val finished = CountDownLatch(1)
            val cleaned = AtomicBoolean(false)
            fun cleanup(mp: MediaPlayer?) {
                if (!cleaned.compareAndSet(false, true)) return
                try {
                    mp?.release()
                } catch (_: Exception) {
                }
                finished.countDown()
            }
            // Ride session exclusive focus; use MEDIA so AAOS routes to audible volume.
            val chimeAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            try {
                val resId = if (entry) R.raw.intro else R.raw.exit
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(chimeAttrs)
                appContext.resources.openRawResourceFd(resId).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                val vol = if (entry) 1.0f else 0.75f
                mp.setVolume(vol, vol)
                mp.setOnCompletionListener { cleanup(it) }
                mp.setOnErrorListener { errored, what, extra ->
                    Log.w(TAG, "chime MediaPlayer error entry=$entry what=$what extra=$extra")
                    cleanup(errored)
                    true
                }
                mp.prepare()
                mp.start()
                Log.d(TAG, "chime played entry=$entry res=$resId")
                finished.await(5, TimeUnit.SECONDS)
            } catch (t: Exception) {
                Log.w(TAG, "chime failed entry=$entry: ${t.message}", t)
                cleanup(player)
            }
        }
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

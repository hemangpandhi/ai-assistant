package com.assistant.ui.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.assistant.ui.assistant.api.AssistantDebugLog

/**
 * Holds exclusive transient audio focus for the life of the assistant overlay.
 *
 * - On [request]: media players receive [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT] and pause.
 * - While held: if play/search steals focus, reclaim so TTS stays audible and media stays paused.
 * - On [abandon] (overlay dismiss): focus returns to media and playback resumes.
 *
 * playMusic's playFromSearch briefly steals focus to bind a track. We mute STREAM_MUSIC for that
 * bind window (and until [abandon]) so the driver does not hear a music blip before the spoken
 * ACK. Media stays paused via exclusive focus; unmute + abandon lets playback resume cleanly.
 */
class AssistantSessionAudioFocus(
    private val context: Context,
) {
    private var focusRequest: AudioFocusRequest? = null
    private var holding = false
    /** True only when this class muted STREAM_MUSIC for a playFromSearch bind window. */
    private var musicMutedForBind = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reclaimRunnable = Runnable {
        if (holding) {
            requestInternal(reclaim = true)
        }
        // Stay muted until abandon — avoids a post-reclaim blip before TTS starts.
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (!holding) return@OnAudioFocusChangeListener
                AssistantDebugLog.d(
                    "Session",
                    "exclusive focus lost ($change) — reclaiming so media stays paused",
                )
                mainHandler.removeCallbacks(reclaimRunnable)
                // Silence immediately; delay only so playFromSearch can bind the new track.
                muteMusicForBind()
                mainHandler.postDelayed(reclaimRunnable, RECLAIM_DELAY_MS)
            }
        }
    }

    fun request() {
        holding = true
        mainHandler.removeCallbacks(reclaimRunnable)
        unmuteMusicAfterBind()
        requestInternal(reclaim = false)
    }

    fun abandon() {
        holding = false
        mainHandler.removeCallbacks(reclaimRunnable)
        unmuteMusicAfterBind()
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
            AssistantDebugLog.d("Session", "exclusive focus abandoned")
        } catch (_: Throwable) {
        }
    }

    private fun muteMusicForBind() {
        if (musicMutedForBind) return
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            ) {
                // Already muted by the user/system — do not take ownership.
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    0,
                )
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
            }
            musicMutedForBind = true
            AssistantDebugLog.d("Session", "music muted for playFromSearch bind window")
        } catch (throwable: Throwable) {
            AssistantDebugLog.w(
                "Session",
                "music mute for bind failed: ${throwable.message}",
            )
        }
    }

    private fun unmuteMusicAfterBind() {
        if (!musicMutedForBind) return
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0,
                )
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
            AssistantDebugLog.d("Session", "music unmuted after exclusive reclaim")
        } catch (throwable: Throwable) {
            AssistantDebugLog.w(
                "Session",
                "music unmute after bind failed: ${throwable.message}",
            )
        } finally {
            musicMutedForBind = false
        }
    }

    private fun requestInternal(reclaim: Boolean) {
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Reclaim reuses the same request (no abandon gap that lets music blip).
                // Fresh request() rebuilds so a stale client is not reused after abandon.
                if (!reclaim || focusRequest == null) {
                    focusRequest?.let { prior ->
                        runCatching { audioManager.abandonAudioFocusRequest(prior) }
                    }
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    focusRequest = AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                    )
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(focusListener, mainHandler)
                        .setAcceptsDelayedFocusGain(false)
                        .build()
                }
                val result = audioManager.requestAudioFocus(requireNotNull(focusRequest))
                AssistantDebugLog.d(
                    "Session",
                    "exclusive focus ${if (reclaim) "reclaimed" else "requested"} result=$result",
                )
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                )
                AssistantDebugLog.d(
                    "Session",
                    "exclusive focus ${if (reclaim) "reclaimed" else "requested"} result=$result",
                )
            }
        } catch (throwable: Throwable) {
            AssistantDebugLog.w(
                "Session",
                "exclusive focus ${if (reclaim) "reclaim" else "request"} failed: ${throwable.message}",
            )
        }
    }

    companion object {
        private const val RECLAIM_DELAY_MS = 250L
    }
}

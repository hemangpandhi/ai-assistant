package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.assistant.ui.assistant.api.AssistantDebugLog

/** Session fallback duck while the agent audio manager is not bound yet. */
internal class AssistantAudioFocusDucker(
    private val context: Context,
) {
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { /* session-owned */ }

    fun request() {
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
            AssistantDebugLog.d("Session", "fallback duck requested")
        } catch (throwable: Throwable) {
            AssistantDebugLog.w("Session", "fallback duck failed: ${throwable.message}")
        }
    }

    fun abandon() {
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
        } catch (_: Throwable) {
        }
    }
}

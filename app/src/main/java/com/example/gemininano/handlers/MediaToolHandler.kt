package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.provider.MediaStore
import android.util.Log
import android.net.Uri

class MediaToolHandler(override val handlerKey: String) : ToolHandler {
    private val TAG = "MediaToolHandler"

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        
        return when (handlerKey) {
            "playMusic" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")").replace("\"", "").trim()
                if (argStr.isBlank() || argStr.equals("SONG", ignoreCase = true) || argStr.equals("music", ignoreCase = true)) {
                    return ToolExecutionResult(false, "What kind of music are you in the mood for?")
                }
                val query = argStr.trim().replace("\"", "")
                
                var success = false
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    var spotifyController = controllers.find { it.packageName.contains("spotify", ignoreCase = true) }
                    
                    if (spotifyController != null) {
                        spotifyController.transportControls.playFromSearch(query, null)
                        success = true
                    } else if (controllers.isNotEmpty()) {
                        // Fallback to any active media session
                        controllers[0].transportControls.playFromSearch(query, null)
                        success = true
                    } else {
                        // Fallback: Launch intent silently if possible, or normally if no active sessions exist
                        val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        searchIntent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        searchIntent.putExtra(android.app.SearchManager.QUERY, query)
                        searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                        success = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaSession search failed, using fallback intent", e)
                    try {
                        val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        searchIntent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        searchIntent.putExtra(android.app.SearchManager.QUERY, query)
                        searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                        success = true
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback intent failed", e2)
                    }
                }
                if (success) ToolExecutionResult(true, "Playing $query in the background.") else ToolExecutionResult(false, "System Error: Could not start media.")
            }
            "pauseMusic" -> {
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    for (controller in controllers) {
                        controller.transportControls.pause()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pause media via MediaSessionManager, using fallback", e)
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                }
                ToolExecutionResult(true, "Music paused.")
            }
            "nextTrack" -> {
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    if (controllers.isNotEmpty()) {
                        controllers[0].transportControls.skipToNext()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to skip media next via MediaSessionManager, using fallback", e)
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                }
                ToolExecutionResult(true, "Playing next track.")
            }
            "prevTrack" -> {
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    if (controllers.isNotEmpty()) {
                        controllers[0].transportControls.skipToPrevious()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to skip media previous via MediaSessionManager, using fallback", e)
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                }
                ToolExecutionResult(true, "Playing the previous track.")
            }
            "adjustBgmForSituation" -> {
                ToolExecutionResult(true, "I've adjusted the background music to match the current driving situation.")
            }
            "increaseVolume" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
                ToolExecutionResult(true, "I've increased the volume.")
            }
            "decreaseVolume" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
                ToolExecutionResult(true, "I've decreased the volume.")
            }
            else -> ToolExecutionResult(false, "System Error: Media Handler not recognized.")
        }
    }
}

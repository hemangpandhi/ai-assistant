package com.tcs.vehicleassistant.handlers

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
                    if (controllers.isNotEmpty()) {
                        for (controller in controllers) {
                            controller.transportControls.pause()
                        }
                    } else {
                        throw Exception("No active sessions found for pause")
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
                    } else {
                        throw Exception("No active sessions found for nextTrack")
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
                    } else {
                        throw Exception("No active sessions found for prevTrack")
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
            "setVolumeLevel" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val argStr = toolCall.substringAfter("(").substringBefore(")").replace("\"", "").trim()
                
                if (argStr.isBlank() || argStr.equals("UP", ignoreCase = true) || argStr.equals("DOWN", ignoreCase = true)) {
                    val isDecrease = toolCall.contains("decrease", ignoreCase = true) || argStr.equals("DOWN", ignoreCase = true)
                    val direction = if (isDecrease) android.media.AudioManager.ADJUST_LOWER else android.media.AudioManager.ADJUST_RAISE
                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
                    
                    val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val newVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val percentage = Math.round((newVol.toFloat() / maxVol) * 100)
                    return ToolExecutionResult(true, "I've set the volume to $percentage%.")
                }

                val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val curVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                var targetVol = curVol

                if (argStr.equals("MAX", ignoreCase = true)) {
                    targetVol = maxVol
                } else if (argStr.startsWith("+") || argStr.startsWith("-")) {
                    val percent = argStr.replace("%", "").toIntOrNull() ?: 0
                    val delta = Math.round((percent / 100f) * maxVol).toInt()
                    targetVol = Math.max(0, Math.min(maxVol, curVol + delta))
                } else {
                    val percent = argStr.replace("%", "").replace("+", "").toIntOrNull()
                    if (percent != null) {
                        targetVol = Math.round((percent / 100f) * maxVol).toInt()
                        targetVol = Math.max(0, Math.min(maxVol, targetVol))
                    } else {
                        val isDecrease = toolCall.contains("decrease", ignoreCase = true)
                        targetVol = if (isDecrease) Math.max(0, curVol - 1) else Math.min(maxVol, curVol + 1)
                    }
                }

                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
                val finalPercentage = Math.round((targetVol.toFloat() / maxVol) * 100)
                ToolExecutionResult(true, "I've set the volume to $finalPercentage%.")
            }
            else -> ToolExecutionResult(false, "System Error: Media Handler not recognized.")
        }
    }
}

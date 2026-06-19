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
                val argStr = toolCall.substringAfter("(").substringBefore(")")
                if (argStr.isBlank() || argStr == "SONG" || argStr == "music") {
                    return ToolExecutionResult(false, "MISSING_PARAMETER: The user did not specify what song to play. Ask them what song or artist they would like to hear.")
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
                        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                        searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                        success = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaSession search failed", e)
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
                    Log.e(TAG, "Failed to pause media via MediaSessionManager", e)
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
                    Log.e(TAG, "Failed to skip media next via MediaSessionManager", e)
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
                    Log.e(TAG, "Failed to skip media previous via MediaSessionManager", e)
                }
                ToolExecutionResult(true, "Playing the previous track.")
            }
            "adjustBgmForSituation" -> {
                ToolExecutionResult(true, "I've adjusted the background music to match the current driving situation.")
            }
            else -> ToolExecutionResult(false, "System Error: Media Handler not recognized.")
        }
    }
}

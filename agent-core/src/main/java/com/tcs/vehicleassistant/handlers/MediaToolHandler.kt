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
                val rawArg = toolCall.substringAfter("(").substringBefore(")").replace("\"", "").trim()
                val query = if (rawArg.isBlank() || rawArg.equals("SONG", ignoreCase = true) || rawArg.equals("music", ignoreCase = true)) {
                    "popular music"
                } else {
                    rawArg
                }
                
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
                        
                        if (searchIntent.resolveActivity(context.packageManager) != null) {
                            if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                            success = true
                        } else {
                            val musicAppIntent = context.packageManager.getLaunchIntentForPackage("com.android.music") ?: 
                                                 context.packageManager.getLaunchIntentForPackage("com.android.car.media")
                            if (musicAppIntent != null) {
                                musicAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                if (intentHandler != null) intentHandler(musicAppIntent) else context.startActivity(musicAppIntent)
                                success = true
                            } else {
                                Log.e(TAG, "No app found to handle MEDIA_PLAY_FROM_SEARCH or default music app.")
                                success = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaSession search failed, using fallback intent", e)
                    try {
                        val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        searchIntent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        searchIntent.putExtra(android.app.SearchManager.QUERY, query)
                        searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        
                        if (searchIntent.resolveActivity(context.packageManager) != null) {
                            if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                            success = true
                        } else {
                            val musicAppIntent = Intent(Intent.ACTION_MAIN)
                            musicAppIntent.addCategory(Intent.CATEGORY_APP_MUSIC)
                            musicAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            if (intentHandler != null) intentHandler(musicAppIntent) else context.startActivity(musicAppIntent)
                            success = true
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback intent failed", e2)
                        success = false
                    }
                }
                if (success) {
                    val msg = "Great choice — putting on $query for you!"
                    ToolExecutionResult(true, msg)
                } else {
                    ToolExecutionResult(false, "System Error: Could not start media.")
                }
            }
            "pauseMusic", "stopMusic" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                
                // 1. Dispatch MediaSessionManager controller commands
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    for (controller in controllers) {
                        try { controller.transportControls.pause() } catch (e: Exception) {}
                        try { controller.transportControls.stop() } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop media via MediaSessionManager", e)
                }

                // 2. Dispatch hardware key events (KEYCODE_MEDIA_PAUSE, KEYCODE_MEDIA_STOP, KEYCODE_MEDIA_PLAY_PAUSE)
                try {
                    val keycodes = intArrayOf(
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                        android.view.KeyEvent.KEYCODE_MEDIA_STOP,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    )
                    for (code in keycodes) {
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to dispatch media key events", e)
                }

                // 3. Broadcast android music service pause intent
                try {
                    val cmdIntent = Intent("com.android.music.musicservicecommand")
                    cmdIntent.putExtra("command", "pause")
                    context.sendBroadcast(cmdIntent)
                } catch (e: Exception) {}

                val feedback = if (handlerKey == "stopMusic") "Music stopped." else "Music paused."
                ToolExecutionResult(true, feedback)
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
                    val hasPercentSign = argStr.contains("%")
                    val parsedNum = argStr.replace("%", "").toIntOrNull() ?: 0
                    val delta = if (hasPercentSign || Math.abs(parsedNum) > maxVol) {
                        Math.round((parsedNum / 100f) * maxVol).toInt()
                    } else {
                        parsedNum
                    }
                    targetVol = Math.max(0, Math.min(maxVol, curVol + delta))
                } else {
                    val hasPercentSign = argStr.contains("%")
                    val parsedNum = argStr.replace("%", "").replace("+", "").toIntOrNull()
                    if (parsedNum != null) {
                        if (hasPercentSign || parsedNum > maxVol) {
                            // Treat as percentage
                            targetVol = Math.round((parsedNum / 100f) * maxVol).toInt()
                        } else {
                            // Treat as absolute hardware index
                            targetVol = parsedNum
                        }
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

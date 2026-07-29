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

                // Artist focus only for person-like queries; genres/moods stay generic search.
                val genreOrMood = listOf(
                    "rock", "jazz", "pop", "classical", "classic", "hip hop", "hip-hop", "rap",
                    "blues", "country", "metal", "edm", "dance", "lofi", "lo-fi", "ambient",
                    "bollywood", "soundtrack", "instrumental", "podcast",
                )
                val looksLikeGenre = genreOrMood.any { token ->
                    query.equals(token, ignoreCase = true) ||
                        query.contains(token, ignoreCase = true)
                }
                val looksLikeArtist = !looksLikeGenre &&
                    !query.contains(" - ") &&
                    query.split(' ').size in 1..4 &&
                    !query.equals("popular music", ignoreCase = true) &&
                    !query.endsWith(" music", ignoreCase = true) &&
                    !query.endsWith(" songs", ignoreCase = true)

                val searchExtras = android.os.Bundle().apply {
                    if (looksLikeArtist) {
                        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                        putString(MediaStore.EXTRA_MEDIA_ARTIST, query)
                    } else {
                        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                        putString(MediaStore.EXTRA_MEDIA_TITLE, query)
                    }
                    putString(android.app.SearchManager.QUERY, query)
                }
                
                var success = false
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    var spotifyController = controllers.find { it.packageName.contains("spotify", ignoreCase = true) }
                    
                    if (spotifyController != null) {
                        Log.i(TAG, "playFromSearch via Spotify query='$query' artistFocus=$looksLikeArtist")
                        spotifyController.transportControls.playFromSearch(query, searchExtras)
                        success = true
                    } else if (controllers.isNotEmpty()) {
                        Log.i(TAG, "playFromSearch via ${controllers[0].packageName} query='$query'")
                        controllers[0].transportControls.playFromSearch(query, searchExtras)
                        success = true
                    } else {
                        // Fallback: Launch intent silently if possible, or normally if no active sessions exist
                        val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        searchIntent.putExtras(searchExtras)
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
                        searchIntent.putExtras(searchExtras)
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
                    val msg = if (query.equals("popular music", ignoreCase = true)) {
                        "Great choice — putting some music on for you!"
                    } else {
                        "Great choice — putting on $query for you!"
                    }
                    ToolExecutionResult(true, msg)
                } else {
                    ToolExecutionResult(false, "System Error: Could not start media.")
                }
            }
            "pauseMusic", "stopMusic" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                var reachedSession = false

                // 1. Dispatch MediaSessionManager controller commands
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    reachedSession = controllers.isNotEmpty()
                    for (controller in controllers) {
                        try { controller.transportControls.pause() } catch (_: Exception) {}
                        try { controller.transportControls.stop() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop media via MediaSessionManager", e)
                }

                // 2. Dispatch explicit hardware pause and stop key events (do NOT send PLAY_PAUSE toggle!)
                try {
                    val keycodes = intArrayOf(
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                        android.view.KeyEvent.KEYCODE_MEDIA_STOP
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
                } catch (_: Exception) {}

                if (!reachedSession) {
                    return ToolExecutionResult(
                        false,
                        "I couldn't find an active media session to control. Is music playing?"
                    )
                }
                val feedback = if (handlerKey == "stopMusic") "Music stopped." else "Music paused."
                ToolExecutionResult(true, feedback)
            }
            "nextTrack" -> {
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    if (controllers.isNotEmpty()) {
                        controllers[0].transportControls.skipToNext()
                        ToolExecutionResult(true, "Playing next track.")
                    } else {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                        ToolExecutionResult(
                            true,
                            "I sent a skip command, but no media session confirmed it."
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to skip media next", e)
                    ToolExecutionResult(false, "I couldn't skip to the next track.")
                }
            }
            "prevTrack" -> {
                try {
                    val controllers = mediaSessionManager.getActiveSessions(null)
                    if (controllers.isNotEmpty()) {
                        controllers[0].transportControls.skipToPrevious()
                        ToolExecutionResult(true, "Playing the previous track.")
                    } else {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                        ToolExecutionResult(
                            true,
                            "I sent a previous-track command, but no media session confirmed it."
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to skip media previous", e)
                    ToolExecutionResult(false, "I couldn't go to the previous track.")
                }
            }
            "adjustBgmForSituation" -> {
                ToolExecutionResult(
                    false,
                    "Situational BGM adjustment isn't wired to a real playlist engine on this build."
                )
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

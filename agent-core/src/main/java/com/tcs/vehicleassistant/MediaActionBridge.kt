package com.tcs.vehicleassistant

import android.content.Context

class MediaActionBridge(private val context: Context) {
    fun isMusicPlaying(): Boolean {
        // Mocking music status
        return false 
    }
    fun skipNext() {
        android.util.Log.d("MediaActionBridge", "Skipping to next track")
    }
    fun playSong(song: String) {
        android.util.Log.d("MediaActionBridge", "Playing song: $song")
    }
}

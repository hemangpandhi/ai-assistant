package com.tcs.vehicleassistant

import android.content.Context

class MediaActionBridge(private val context: Context) {
    fun isMusicPlaying(): Boolean = false
    fun skipNext() {}
    fun playSong(song: String) {}
}

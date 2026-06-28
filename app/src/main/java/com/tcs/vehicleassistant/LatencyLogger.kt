package com.tcs.vehicleassistant

import android.util.Log

object LatencyLogger {
    private const val TAG = "LLMLatency"
    private var lastLogTimeMs: Long = 0
    var startTimeMs: Long = 0
    var lastAsrTimeMs: Long = 0
    var lastToolTimeMs: Long = 0
    var userSpeakingStartTimeMs: Long = 0
    var userSpeakingEndTimeMs: Long = 0

    fun reset() {
        val now = System.currentTimeMillis()
        lastLogTimeMs = now
        startTimeMs = now
        lastAsrTimeMs = 0
        lastToolTimeMs = 0
        userSpeakingStartTimeMs = 0
        userSpeakingEndTimeMs = 0
    }

    fun getTotalTime(): Long {
        return if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
    }

    fun log(component: String, message: String) {
        val now = System.currentTimeMillis()
        val delta = if (lastLogTimeMs > 0) now - lastLogTimeMs else 0
        val total = if (startTimeMs > 0) now - startTimeMs else 0
        lastLogTimeMs = now
        Log.i(TAG, "[$component] $message (+${delta}ms) [Total: ${total}ms]")
    }
}

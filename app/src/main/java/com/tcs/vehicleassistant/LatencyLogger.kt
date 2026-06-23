package com.tcs.vehicleassistant

import android.util.Log

object LatencyLogger {
    private const val TAG = "LLMLatency"
    private var lastLogTimeMs: Long = 0

    fun reset() {
        lastLogTimeMs = System.currentTimeMillis()
    }

    fun log(component: String, message: String) {
        val now = System.currentTimeMillis()
        val delta = if (lastLogTimeMs > 0) now - lastLogTimeMs else 0
        lastLogTimeMs = now
        Log.i(TAG, "[$component] $message (+${delta}ms)")
    }
}

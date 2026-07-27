package com.assistant.ui.assistant.ui.immersive

import android.os.SystemClock
import android.util.Log

/**
 * Marks for Compose UI time-to-first-frame (session overlay).
 * Target: first visible paint &lt; 100ms from content-view inflate.
 */
object AssistantUiLatency {
    private const val TAG = "AssistantUiLatency"

    @Volatile
    private var markStartMs: Long = 0

    fun markContentViewStart() {
        markStartMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "contentView start")
    }

    fun mark(label: String) {
        val start = markStartMs
        if (start <= 0L) {
            Log.i(TAG, label)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "$label (+${elapsed}ms)")
    }

    fun elapsedMs(): Long {
        val start = markStartMs
        return if (start <= 0L) 0L else SystemClock.elapsedRealtime() - start
    }
}

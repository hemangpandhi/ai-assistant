package com.assistant.ui.assistant.api

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring-buffer of recent assistant debug lines for on-overlay display.
 * Host / backend / session push here; Compose collects [lines].
 */
object AssistantDebugLog {
    private const val CAPACITY = 10
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun clear() {
        _lines.value = emptyList()
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        push("D", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        push("W", tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        push("E", tag, message)
    }

    private fun push(level: String, tag: String, message: String) {
        val stamp = timeFmt.format(Date())
        val line = "$stamp $level/$tag: $message"
        _lines.update { cur -> (cur + line).takeLast(CAPACITY) }
    }
}

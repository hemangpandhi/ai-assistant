package com.tcs.vehicleassistant.utils

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object EmergencyAlarmManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var alarmJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    fun start(context: Context, durationMs: Long = 6000L) {
        stop()
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
            return
        }
        alarmJob = scope.launch {
            val end = System.currentTimeMillis() + durationMs
            while (isActive && System.currentTimeMillis() < end) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
                } catch (_: Exception) {}
                delay(2000)
            }
            stop()
        }
    }

    fun stop() {
        alarmJob?.cancel()
        alarmJob = null
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
    }
}

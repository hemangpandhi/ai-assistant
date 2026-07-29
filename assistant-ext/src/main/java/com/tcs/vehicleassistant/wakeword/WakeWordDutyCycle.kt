package com.tcs.vehicleassistant.wakeword

import kotlin.math.abs

/**
 * Quiet-cabin duty cycling for the Vosk wake loop (UI/UX extension).
 *
 * When amplitude stays below [silenceThreshold], only 1 of every [silentSkip]
 * buffers is passed to the recognizer — reducing CPU when the cabin is quiet.
 */
class WakeWordDutyCycle(
    private val silenceThreshold: Int = 180,
    private val silentSkip: Int = 4,
) {
    data class Decision(
        val maxAmplitude: Int,
        val shouldRecognize: Boolean,
    )

    private var silentBuffers: Int = 0

    fun reset() {
        silentBuffers = 0
    }

    fun inspect(buffer: ShortArray, size: Int): Decision {
        var maxAmplitude = 0
        for (i in 0 until size) {
            val a = abs(buffer[i].toInt())
            if (a > maxAmplitude) maxAmplitude = a
        }
        val shouldRecognize = if (maxAmplitude < silenceThreshold) {
            silentBuffers++
            silentBuffers % silentSkip == 0
        } else {
            silentBuffers = 0
            true
        }
        return Decision(maxAmplitude = maxAmplitude, shouldRecognize = shouldRecognize)
    }
}

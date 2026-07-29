package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cross-process Vosk mic hold marker + generation gate (UI/UX extension).
 *
 * Extracted from [com.tcs.vehicleassistant.WakeWordService] so the wake-word service
 * shell can stay closer to `dev/refactor` while handoff / STT pre-arm keeps working
 * across the `:wakeword` process boundary.
 */
object CrossProcessMicLease {
    private const val TAG = "MicLease"
    private const val MIC_HOLD_MARKER = ".vosk_mic_holding"

    @Volatile
    private var holdContext: Context? = null

    @Volatile
    private var holdingInProcess: Boolean = false

    private val micHoldGeneration = AtomicInteger(0)

    @Volatile
    private var releaseGate: CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }

    val isHoldingMic: Boolean
        get() {
            if (holdingInProcess) return true
            val marker = holdMarkerFile() ?: return false
            return marker.exists()
        }

    private fun holdMarkerFile(): File? {
        val ctx = holdContext ?: return null
        return File(ctx.filesDir, MIC_HOLD_MARKER)
    }

    fun bindHoldContext(context: Context) {
        holdContext = context.applicationContext
    }

    /** @return hold generation that must be passed to [signalMicReleased]. */
    fun beginMicHold(): Int {
        val gen = micHoldGeneration.incrementAndGet()
        if (releaseGate.isCompleted) {
            releaseGate = CompletableDeferred()
        }
        holdingInProcess = true
        try {
            holdMarkerFile()?.writeText(gen.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write mic hold marker", e)
        }
        return gen
    }

    fun signalMicReleased(generation: Int) {
        if (generation != micHoldGeneration.get()) {
            Log.d(TAG, "ignore stale mic release gen=$generation current=${micHoldGeneration.get()}")
            return
        }
        holdingInProcess = false
        try {
            holdMarkerFile()?.delete()
        } catch (_: Exception) {
        }
        if (!releaseGate.isCompleted) {
            releaseGate.complete(Unit)
        }
    }

    /** Force-open the gate after an intentional stop (invalidates in-flight holds). */
    fun forceReleaseMic() {
        micHoldGeneration.incrementAndGet()
        holdingInProcess = false
        try {
            holdMarkerFile()?.delete()
        } catch (_: Exception) {
        }
        if (!releaseGate.isCompleted) {
            releaseGate.complete(Unit)
        }
    }

    /** Suspend until Vosk releases the mic, or [timeoutMs] elapses. Cross-process safe. */
    suspend fun awaitMicReleased(timeoutMs: Long = 1000L): Boolean {
        if (!isHoldingMic) {
            if (releaseGate.isCompleted) {
                return true
            }
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isHoldingMic) return@withTimeoutOrNull true
                kotlinx.coroutines.delay(20)
            }
            !isHoldingMic
        } == true || !isHoldingMic
    }
}

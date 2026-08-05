package com.tcs.vehicleassistant.hardware

/**
 * UI/UX extension of [IAudioManager] for the session-owned ear path.
 * Keeps the master [IAudioManager] byte-identical.
 */
interface SessionAudioPort : IAudioManager {
    /**
     * Pre-allocate standby [android.media.AudioRecord] and load STT/VAD before the
     * user speaks. Idempotent.
     */
    fun prewarmEar()

    /**
     * Start listening, cancelling any in-flight capture first.
     * Fixes the silent no-op when a prior listen left `isListening=true`.
     */
    fun startListeningForced()
}

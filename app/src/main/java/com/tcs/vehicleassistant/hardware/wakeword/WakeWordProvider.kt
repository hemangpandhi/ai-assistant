package com.tcs.vehicleassistant.hardware.wakeword

interface WakeWordProvider {
    /**
     * Initializes the wake word detection engine.
     */
    fun initialize(onSuccess: () -> Unit, onError: (Exception) -> Unit)

    /**
     * Starts listening for the wake word.
     */
    fun startListening()

    /**
     * Stops listening for the wake word.
     */
    fun stopListening()

    /**
     * Registers a callback to be invoked when the wake word is detected.
     */
    fun setOnWakeWordDetectedListener(listener: () -> Unit)

    /**
     * Releases any resources held by the provider.
     */
    fun destroy()
}

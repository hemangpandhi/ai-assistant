package com.tcs.vehicleassistant.hardware

/**
 * Abstracts cabin audio: TTS playback and session speech recognition (ear).
 *
 * STT is owned by [com.tcs.vehicleassistant.hardware.ear.AssistantEar]; this port
 * is the single dependency for ViewModel / orchestrator (ISP / DIP).
 */
interface IAudioManager {

    fun initialize(onSuccess: () -> Unit, onError: () -> Unit)

    /** Pre-allocate standby mic + load STT/VAD (idempotent). */
    fun prewarmEar()

    fun startListening()

    /** Cancel in-flight capture and start a new utterance (no silent no-op). */
    fun startListeningForced()

    fun stopListening()

    /**
     * Release mic + STT engines (session hide / wake-word handoff).
     */
    fun destroySpeechRecognizer()

    fun speak(text: String, utteranceId: String)

    fun reloadTtsFromPrefs()

    fun playSilentUtterance(durationMs: Long, utteranceId: String)

    fun stopSpeaking()

    suspend fun waitUntilFinishedSpeaking()

    fun shutdown()

    fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onRangeStart: (String, Int, Int, Int) -> Unit,
    )

    fun setRecognitionListener(
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onEndOfSpeech: () -> Unit,
        onResult: (String) -> Unit,
        onEmptyResult: () -> Unit,
        onError: (Int) -> Unit,
        onPartial: (String) -> Unit,
    )
}

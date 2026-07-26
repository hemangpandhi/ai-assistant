package com.test.design.assistant.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for an assistant brain — demo script today, remote/LLM later.
 *
 * The Compose UI never imports IVI or networking; it only collects [events]
 * and forwards mic / thumbs into this interface.
 */
interface AssistantBackend {
    val events: Flow<AssistantSessionEvent>

    /** True while a session is driving the immersive stage. */
    val sessionActive: StateFlow<Boolean>

    fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig = AssistantSessionConfig(),
    )

    fun stopSession()

    /** Forward on-device (or remote) speech recognizer events. */
    fun onSpeechInput(input: AssistantSpeechInput)

    fun onThumbsFeedback(positive: Boolean)
}

/**
 * Optional TTS sink — backends may speak through the host audio path.
 * Returning a lip-sync amplitude flow keeps the face in sync.
 */
interface AssistantTtsEngine {
    /**
     * Speak [text]. Emit mouth-open amplitude 0..1 while speaking.
     * Empty / no-op flow = silent lip-sync only.
     */
    fun speak(text: String, holdMs: Long): Flow<Float>
}

/**
 * Optional STT source. Demo uses Android SpeechRecognizer; production may
 * swap in a cloud / always-on hotword pipeline.
 */
interface AssistantSttEngine {
    fun speechInputs(): Flow<AssistantSpeechInput>
}

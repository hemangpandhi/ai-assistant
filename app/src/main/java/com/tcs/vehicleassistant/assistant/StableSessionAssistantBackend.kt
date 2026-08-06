package com.tcs.vehicleassistant.assistant

import com.assistant.ui.assistant.api.AssistantBackend
import com.assistant.ui.assistant.api.AssistantCabinContext
import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.AssistantSessionConfig
import com.assistant.ui.assistant.api.AssistantSessionEvent
import com.assistant.ui.assistant.api.AssistantSpeaker
import com.assistant.ui.assistant.api.AssistantSpeechInput
import com.assistant.ui.assistant.api.AssistantStartReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin UI event bridge for this branch's [com.tcs.vehicleassistant.AssistantSession].
 * Compose collects [events]; STT / LLM / TTS stay on the existing session path.
 */
class StableSessionAssistantBackend : AssistantBackend {

    private val _events = MutableSharedFlow<AssistantSessionEvent>(extraBufferCapacity = 64)
    override val events: Flow<AssistantSessionEvent> = _events.asSharedFlow()

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    override fun startSession(
        reason: AssistantStartReason,
        cabin: AssistantCabinContext,
        config: AssistantSessionConfig,
    ) {
        _sessionActive.value = true
    }

    override fun stopSession() {
        _sessionActive.value = false
    }

    override fun onSpeechInput(input: AssistantSpeechInput) = Unit

    override fun onThumbsFeedback(positive: Boolean) = Unit

    fun emitMood(mood: AssistantMoodId) {
        _events.tryEmit(AssistantSessionEvent.MoodChanged(mood))
    }

    fun emitTranscript(text: String, speaker: AssistantSpeaker) {
        _events.tryEmit(AssistantSessionEvent.Transcript(text = text, speaker = speaker))
    }

    fun emitMouth(amplitude: Float?) {
        _events.tryEmit(AssistantSessionEvent.MouthAmplitude(amplitude))
    }

    fun emitError(message: String) {
        _events.tryEmit(AssistantSessionEvent.Error(message))
    }
}

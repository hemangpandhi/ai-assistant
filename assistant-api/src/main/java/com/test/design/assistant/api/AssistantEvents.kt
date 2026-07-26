package com.test.design.assistant.api

/**
 * Events the UI observes from [AssistantBackend].
 * The immersive overlay maps these onto face / transcript state — UI stays dumb.
 */
sealed interface AssistantSessionEvent {
    data class MoodChanged(val mood: AssistantMoodId) : AssistantSessionEvent

    data class Transcript(
        val text: String,
        val speaker: AssistantSpeaker,
    ) : AssistantSessionEvent

    data class Gaze(
        val x: Float?,
        val y: Float?,
    ) : AssistantSessionEvent

    data class GestureChanged(val gesture: AssistantGesture) : AssistantSessionEvent

    data class MouthAmplitude(val value: Float?) : AssistantSessionEvent

    data class ThumbsVisible(val visible: Boolean) : AssistantSessionEvent

    /** Weather / climate context glyph for Fusion Eyes; null clears. */
    data class ContextGlyph(val glyph: AssistantContextGlyph?) : AssistantSessionEvent

    data class PresentationHint(val hint: AssistantPresentationHint) : AssistantSessionEvent

    /** Host should mirror a glanceable status to the cluster. */
    data object RequestClusterHandOff : AssistantSessionEvent

    /** Session finished — UI should dismiss immersive chrome. */
    data object SessionComplete : AssistantSessionEvent
}

/** Live mic / ASR stream from the device (or a remote STT adapter). */
sealed interface AssistantSpeechInput {
    data object Hotword : AssistantSpeechInput
    data class Partial(val text: String) : AssistantSpeechInput
    data class Final(val text: String) : AssistantSpeechInput
    data class Rms(val normalized: Float) : AssistantSpeechInput
}

package com.assistant.ui.assistant.api

import androidx.compose.runtime.Immutable

/**
 * Events the UI observes from [AssistantBackend].
 * The immersive overlay maps these onto face / transcript state — UI stays dumb.
 */
@Immutable
sealed interface AssistantSessionEvent {
    @Immutable
    data class MoodChanged(val mood: AssistantMoodId) : AssistantSessionEvent

    @Immutable
    data class Transcript(
        val text: String,
        val speaker: AssistantSpeaker,
    ) : AssistantSessionEvent

    @Immutable
    data class Gaze(
        val x: Float?,
        val y: Float?,
    ) : AssistantSessionEvent

    @Immutable
    data class GestureChanged(val gesture: AssistantGesture) : AssistantSessionEvent

    @Immutable
    data class MouthAmplitude(val value: Float?) : AssistantSessionEvent

    @Immutable
    data class ThumbsVisible(val visible: Boolean) : AssistantSessionEvent

    /** Weather / climate context glyph for Fusion Eyes; null clears. */
    @Immutable
    data class ContextGlyph(val glyph: AssistantContextGlyph?) : AssistantSessionEvent

    /**
     * LLM-owned anatomy cues (per-eye / mouth / L-R accents).
     * Null or [AssistantFaceCues.Empty] clears back to geometric face parts.
     */
    @Immutable
    data class FaceCuesChanged(val cues: AssistantFaceCues?) : AssistantSessionEvent

    @Immutable
    data class PresentationHint(val hint: AssistantPresentationHint) : AssistantSessionEvent

    /** Host should mirror a glanceable status to the cluster. */
    @Immutable
    data object RequestClusterHandOff : AssistantSessionEvent

    /** Session finished — UI should dismiss immersive chrome. */
    @Immutable
    data object SessionComplete : AssistantSessionEvent

    /** Fatal / recoverable failure — shown in debug chrome and as transcript. */
    @Immutable
    data class Error(val message: String) : AssistantSessionEvent
}

/** Live mic / ASR stream from the device (or a remote STT adapter). */
@Immutable
sealed interface AssistantSpeechInput {
    @Immutable
    data object Hotword : AssistantSpeechInput

    @Immutable
    data class Partial(val text: String) : AssistantSpeechInput

    @Immutable
    data class Final(val text: String) : AssistantSpeechInput

    @Immutable
    data class Rms(val normalized: Float) : AssistantSpeechInput
}

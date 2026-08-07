package com.assistant.ui.assistant.mvi

import androidx.compose.runtime.Immutable
import com.assistant.ui.assistant.api.AssistantContextGlyph
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.api.AssistantSessionEvent
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.ui.chrome.AssistantPresentation
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.backend.toUiMood
import com.assistant.ui.assistant.backend.toUiSpeaker
import com.assistant.ui.assistant.backend.toUiGesture

/**
 * Presentation-layer MVI for the immersive Compose stage.
 * Pure reduce — no Android / agent dependencies.
 */
@Immutable
data class StageState(
    val visible: Boolean = false,
    val session: Int = 0,
    val presentation: AssistantPresentation = AssistantPresentation.Immersive,
    val mood: AssistantMood = AssistantMood.Idle,
    val transcript: String = "",
    val speaker: DialogueSpeaker = DialogueSpeaker.System,
    val gazeX: Float? = -0.42f,
    val gazeY: Float? = 0.05f,
    val mouthAmplitude: Float? = null,
    val gesture: FaceGesture = FaceGesture.None,
    val showThumbs: Boolean = false,
    val thumbsTick: Int = 0,
    val contextGlyph: AssistantContextGlyph? = null,
    val glyphGazeActive: Boolean = false,
    /** LLM anatomy cues; null / empty → geometric eyes & mouth. */
    val faceCues: AssistantFaceCues? = null,
    val lastError: String? = null,
)

sealed interface StageIntent {
    data object Summon : StageIntent
    data object Dismiss : StageIntent
    data class BackendEvent(val event: AssistantSessionEvent) : StageIntent
    data class Thumbs(val positive: Boolean) : StageIntent
    data class SetVisible(val visible: Boolean) : StageIntent
}

sealed interface StageEffect {
    data object RequestListen : StageEffect
    data object ClusterHandOff : StageEffect
    data object FinishSession : StageEffect
    data object StopSession : StageEffect
}

fun reduceStage(state: StageState, intent: StageIntent): Pair<StageState, List<StageEffect>> {
    return when (intent) {
        StageIntent.Summon -> state.copy(
            visible = true,
            session = state.session + 1,
            mood = AssistantMood.Listening,
            // Blank until live STT partials — no "Listening…" status caption.
            transcript = "",
            speaker = DialogueSpeaker.User,
            lastError = null,
            mouthAmplitude = null,
            faceCues = null,
        ) to listOf(StageEffect.RequestListen)

        StageIntent.Dismiss -> state.copy(visible = false) to listOf(StageEffect.StopSession)

        is StageIntent.SetVisible -> state.copy(visible = intent.visible) to emptyList()

        is StageIntent.Thumbs -> state.copy(
            showThumbs = false,
            thumbsTick = state.thumbsTick + 1,
        ) to emptyList()

        is StageIntent.BackendEvent -> reduceBackendEvent(state, intent.event)
    }
}

private fun reduceBackendEvent(
    state: StageState,
    event: AssistantSessionEvent,
): Pair<StageState, List<StageEffect>> {
    return when (event) {
        is AssistantSessionEvent.MoodChanged ->
            state.copy(mood = event.mood.toUiMood()) to emptyList()

        is AssistantSessionEvent.Transcript ->
            state.copy(
                transcript = event.text,
                speaker = event.speaker.toUiSpeaker(),
                lastError = null,
            ) to emptyList()

        is AssistantSessionEvent.Error ->
            state.copy(
                lastError = event.message,
                mood = AssistantMood.Sad,
                mouthAmplitude = null,
            ) to emptyList()

        is AssistantSessionEvent.Gaze ->
            state.copy(gazeX = event.x, gazeY = event.y) to emptyList()

        is AssistantSessionEvent.GestureChanged ->
            state.copy(gesture = event.gesture.toUiGesture()) to emptyList()

        is AssistantSessionEvent.MouthAmplitude ->
            state.copy(mouthAmplitude = event.value) to emptyList()

        is AssistantSessionEvent.ThumbsVisible ->
            state.copy(
                showThumbs = event.visible,
                thumbsTick = if (event.visible) state.thumbsTick + 1 else state.thumbsTick,
            ) to emptyList()

        is AssistantSessionEvent.ContextGlyph ->
            state.copy(
                contextGlyph = event.glyph,
                glyphGazeActive = event.glyph != null,
            ) to emptyList()

        is AssistantSessionEvent.FaceCuesChanged ->
            state.copy(
                faceCues = event.cues?.takeUnless { it.isEmpty },
            ) to emptyList()

        is AssistantSessionEvent.PresentationHint -> state to emptyList()

        AssistantSessionEvent.RequestClusterHandOff ->
            state to listOf(StageEffect.ClusterHandOff)

        AssistantSessionEvent.SessionComplete ->
            state.copy(
                visible = false,
                mood = AssistantMood.Idle,
                mouthAmplitude = null,
                faceCues = null,
            ) to listOf(StageEffect.FinishSession, StageEffect.StopSession)
    }
}

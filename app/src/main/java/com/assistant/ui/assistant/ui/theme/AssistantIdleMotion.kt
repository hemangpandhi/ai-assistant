package com.assistant.ui.assistant.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.assistant.ui.assistant.face.AssistantMood

/**
 * When false, faces skip infinite idle loops and soft blur blooms so the first
 * Compose frame can paint under ~100ms — and so Thinking / Listening / Speaking
 * do not keep the GPU busy while LiteRT needs it for TTFT.
 *
 * The immersive overlay sets this from [richEffects] and the current mood.
 */
val LocalAssistantIdleMotion = staticCompositionLocalOf { true }

/** Moods where continuous Canvas motion must yield GPU to the on-device model. */
fun AssistantMood.yieldsGpuForInference(): Boolean = when (this) {
    AssistantMood.Thinking,
    AssistantMood.Concentration,
    AssistantMood.Searching,
    AssistantMood.Reading,
    AssistantMood.Listening,
    AssistantMood.Speaking,
    -> true
    else -> false
}

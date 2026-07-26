package com.test.design.presentation.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Renders the active [AssistantFaceKind] from [AssistantFaceConfig] (or an override).
 * [AssistantFaceKind.None] draws nothing — transcript / chrome still show.
 * Thinking mood shows a shared in/out thought cloud at the face top-right.
 */
@Composable
fun ConfigurableAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    kind: AssistantFaceKind? = null,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = Color(0xFF8AB4F8),
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
) {
    val configured by AssistantFaceConfig.kind.collectAsStateWithLifecycle()
    val resolved = kind ?: configured
    if (resolved == AssistantFaceKind.None) {
        Box(modifier)
        return
    }

    FaceWithThinkingCloud(mood = mood, modifier = modifier) {
        when (resolved) {
            AssistantFaceKind.None -> Unit
            AssistantFaceKind.ImmersiveEyes -> ImmersiveEyesFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
            )
            AssistantFaceKind.ImmersiveGlow -> ImmersiveGlowEyesFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
            )
            AssistantFaceKind.ImmersiveHybrid -> ImmersiveHybridEyesFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
            )
            AssistantFaceKind.Eporo -> EporoAssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
            )
            AssistantFaceKind.Fusion -> FusionAssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
            )
            AssistantFaceKind.FusionGlow -> FusionGlowAssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
            )
            AssistantFaceKind.FusionEyes -> FusionEyesAssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
            )
            AssistantFaceKind.Droid -> DroidAssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
            )
            AssistantFaceKind.Glyph -> AssistantFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

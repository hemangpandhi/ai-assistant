package com.assistant.ui.assistant.face

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.ui.chrome.FaceWithThinkingCloud
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Renders the active [AssistantFaceKind] from [AssistantFaceConfig] (or an override).
 * [AssistantFaceKind.None] draws nothing — transcript / chrome still show.
 * Thinking mood shows a shared in/out thought cloud at the face top-right.
 *
 * [faceCues] applies to Immersive / Fusion eye faces on the main overlay only.
 * [showShell] false = eyes/mouth only (Dynamic Island); skips SemiCircle face plate.
 */
@Composable
fun ConfigurableAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    kind: AssistantFaceKind? = null,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = AssistantTokens.Accent,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    faceCues: AssistantFaceCues? = null,
    showShell: Boolean = true,
) {
    val configured by AssistantFaceConfig.kind.collectAsStateWithLifecycle()
    val resolved = kind ?: configured
    if (resolved == AssistantFaceKind.None) {
        Box(modifier)
        return
    }

    FaceWithThinkingCloud(mood = mood, modifier = modifier) {
        // Island mode: always glyph eyes without the SemiCircle / EPORO head plate.
        if (!showShell) {
            when (resolved) {
                AssistantFaceKind.ImmersiveGlow,
                AssistantFaceKind.FusionGlow,
                -> ImmersiveGlowEyesFace(
                    mood = mood,
                    modifier = Modifier.fillMaxSize(),
                    gazeX = gazeX,
                    gazeY = gazeY,
                    mouthAmplitude = mouthAmplitude,
                    brandGlow = brandGlow,
                    highContrast = highContrast,
                    gesture = gesture,
                    faceCues = faceCues,
                    showShell = false,
                )
                AssistantFaceKind.ImmersiveHybrid,
                AssistantFaceKind.ImmersiveTrapezoid,
                -> ImmersiveHybridEyesFace(
                    mood = mood,
                    modifier = Modifier.fillMaxSize(),
                    gazeX = gazeX,
                    gazeY = gazeY,
                    mouthAmplitude = mouthAmplitude,
                    brandGlow = brandGlow,
                    highContrast = highContrast,
                    gesture = gesture,
                    faceCues = faceCues,
                    showShell = false,
                )
                else -> ImmersiveEyesFace(
                    mood = mood,
                    modifier = Modifier.fillMaxSize(),
                    gazeX = gazeX,
                    gazeY = gazeY,
                    mouthAmplitude = mouthAmplitude,
                    brandGlow = brandGlow,
                    highContrast = highContrast,
                    gesture = gesture,
                    faceCues = faceCues,
                    showShell = false,
                )
            }
            return@FaceWithThinkingCloud
        }

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
                faceCues = faceCues,
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
                faceCues = faceCues,
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
                faceCues = faceCues,
            )
            AssistantFaceKind.ImmersiveTrapezoid -> ImmersiveTrapezoidEyesFace(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
                faceCues = faceCues,
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
                faceCues = faceCues,
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
                faceCues = faceCues,
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
                faceCues = faceCues,
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

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
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Renders the active [AssistantFaceKind] from [AssistantFaceConfig] (or an override).
 * [AssistantFaceKind.None] draws nothing — transcript / chrome still show.
 *
 * Thinking mood is expressed through eye pose / gaze (no thought-cloud prop).
 *
 * [faceCues] applies to Immersive / Fusion eye faces on the main overlay only.
 * [showShell] false = island capsule eyes only (no mouth / SemiCircle plate);
 * any non-empty [faceCues] slot shows in a badge to the right of the eyes.
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

    // Island mode: always glyph eyes without the SemiCircle / EPORO head plate.
    // ClassicHybrid is the exception — keep the master SemiCircle shell.
    if (!showShell) {
        when (resolved) {
            AssistantFaceKind.ClassicHybrid -> ClassicHybridEyesFace(
                mood = mood,
                modifier = modifier.fillMaxSize(),
                gazeX = gazeX,
                gazeY = gazeY,
                mouthAmplitude = mouthAmplitude,
                brandGlow = brandGlow,
                highContrast = highContrast,
                gesture = gesture,
                faceCues = faceCues,
            )
            AssistantFaceKind.ImmersiveGlow,
            AssistantFaceKind.FusionGlow,
            -> ImmersiveGlowEyesFace(
                mood = mood,
                modifier = modifier.fillMaxSize(),
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
                modifier = modifier.fillMaxSize(),
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
                modifier = modifier.fillMaxSize(),
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
        return
    }

    when (resolved) {
        AssistantFaceKind.None -> Unit
        AssistantFaceKind.ImmersiveEyes -> ImmersiveEyesFace(
            mood = mood,
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
            gazeX = gazeX,
            gazeY = gazeY,
            mouthAmplitude = mouthAmplitude,
            brandGlow = brandGlow,
            highContrast = highContrast,
            gesture = gesture,
            faceCues = faceCues,
        )
        AssistantFaceKind.ClassicHybrid -> ClassicHybridEyesFace(
            mood = mood,
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
        )
        AssistantFaceKind.Fusion -> FusionAssistantFace(
            mood = mood,
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
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
            modifier = modifier.fillMaxSize(),
        )
        AssistantFaceKind.Glyph -> AssistantFace(
            mood = mood,
            modifier = modifier.fillMaxSize(),
        )
    }
}

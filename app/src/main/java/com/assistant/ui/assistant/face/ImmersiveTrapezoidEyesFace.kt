package com.assistant.ui.assistant.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.ui.chrome.FaceGesture

/**
 * Main-overlay immersive face with a fixed isosceles trapezoid shell
 * (wider base, 45° base angles, light corner rounding).
 *
 * Eyes / mouth / cues / hybrid purple-glow behavior match [ImmersiveHybridEyesFace];
 * only the outer plate differs. Switch back with face token `hybrid`.
 */
@Composable
fun ImmersiveTrapezoidEyesFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = EporoGlow,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    faceCues: AssistantFaceCues? = null,
) {
    val glowStrength = remember {
        Animatable(if (mood.usesImmersivePurpleGlow()) 1f else 0f)
    }
    LaunchedEffect(mood) {
        glowStrength.animateTo(
            targetValue = if (mood.usesImmersivePurpleGlow()) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.92f,
                stiffness = 90f,
            ),
        )
    }

    ImmersiveEyesFace(
        mood = mood,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        eyeGlow = EporoGlow.copy(alpha = glowStrength.value.coerceIn(0f, 1f)),
        faceCues = faceCues,
        shellKind = ExpressiveShellKind.Trapezoid,
    )
}

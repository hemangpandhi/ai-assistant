package com.assistant.ui.assistant.face

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.ui.chrome.FaceGesture

/**
 * Master-style SemiCircle hybrid overlay: mood-driven pale ↔ purple capsule eyes
 * on the full black shell plate.
 *
 * Always draws with [showShell] true. Bottom chrome routes this kind through
 * [com.assistant.ui.assistant.ui.immersive.FaceStageDock] (text below, no island).
 * Enable with face token `classichybrid`.
 */
@Composable
fun ClassicHybridEyesFace(
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
    ImmersiveHybridEyesFace(
        mood = mood,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        faceCues = faceCues,
        showShell = true,
    )
}

package com.assistant.ui.assistant.ui.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun runImmersiveCardEnter(
    backdropAlpha: Animatable<Float, AnimationVector1D>,
    faceRise: Animatable<Float, AnimationVector1D>,
    faceScale: Animatable<Float, AnimationVector1D>,
    overlayReveal: Animatable<Float, AnimationVector1D>,
    targetBackdropAlpha: Float,
) = coroutineScope {
    // Edge slide for card chrome — no icon expand / hotword wipe.
    backdropAlpha.snapTo(0f)
    faceRise.snapTo(0f)
    faceScale.snapTo(AssistantOverlayTokens.CardStartScale)
    try {
        launch {
            backdropAlpha.animateTo(
                targetBackdropAlpha,
                tween(AssistantOverlayTokens.CardBackdropMs, easing = FastOutSlowInEasing),
            )
        }
        launch {
            faceScale.animateTo(
                1f,
                spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium),
            )
        }
        overlayReveal.animateTo(
            1f,
            tween(AssistantOverlayTokens.CardRevealMs, easing = FastOutSlowInEasing),
        )
    } finally {
        if (overlayReveal.value < 0.99f) overlayReveal.snapTo(1f)
        if (backdropAlpha.value < targetBackdropAlpha * 0.95f) {
            backdropAlpha.snapTo(targetBackdropAlpha)
        }
    }
}

/**
 * Face: bottom → center peak → settle home (wake word + icon launch).
 * Uses M3 expressive slow spatial springs (physics, not fixed keyframes).
 */
internal suspend fun runImmersiveFullscreenEnter(
    backdropAlpha: Animatable<Float, AnimationVector1D>,
    faceRise: Animatable<Float, AnimationVector1D>,
    faceScale: Animatable<Float, AnimationVector1D>,
    faceAlpha: Animatable<Float, AnimationVector1D>,
    overlayReveal: Animatable<Float, AnimationVector1D>,
    spatialSpec: AnimationSpec<Float>,
    effectsSpec: AnimationSpec<Float>,
) = coroutineScope {
    backdropAlpha.snapTo(1f)
    faceRise.snapTo(-1f)
    faceScale.snapTo(AssistantOverlayTokens.FaceStartScale)
    faceAlpha.snapTo(1f)
    try {
        launch {
            overlayReveal.animateTo(1f, effectsSpec)
        }
        launch {
            // Soft scale punch travels with the spatial path.
            faceScale.animateTo(AssistantOverlayTokens.FacePeakScale, spatialSpec)
            faceScale.animateTo(1f, spatialSpec)
        }
        // Bottom → center, then spring down to settled home.
        faceRise.animateTo(1f, spatialSpec)
        faceRise.animateTo(0f, spatialSpec)
    } finally {
        if (overlayReveal.value < 0.99f) overlayReveal.snapTo(1f)
        if (faceRise.value !in -0.02f..0.02f) faceRise.snapTo(0f)
        if (faceScale.value !in 0.98f..1.02f) faceScale.snapTo(1f)
    }
}

internal suspend fun fadeInImmersiveTranscript(
    transcriptAlpha: Animatable<Float, AnimationVector1D>,
    effectsSpec: AnimationSpec<Float>,
) {
    delay(AssistantOverlayTokens.TranscriptFadeInDelayMs)
    transcriptAlpha.animateTo(1f, effectsSpec)
}

internal suspend fun runImmersiveExit(
    backdropAlpha: Animatable<Float, AnimationVector1D>,
    faceRise: Animatable<Float, AnimationVector1D>,
    faceScale: Animatable<Float, AnimationVector1D>,
    faceAlpha: Animatable<Float, AnimationVector1D>,
    transcriptAlpha: Animatable<Float, AnimationVector1D>,
    overlayReveal: Animatable<Float, AnimationVector1D>,
    spatialSpec: AnimationSpec<Float>,
    effectsSlowSpec: AnimationSpec<Float>,
    effectsDefaultSpec: AnimationSpec<Float>,
) = coroutineScope {
    transcriptAlpha.animateTo(0f, effectsDefaultSpec)
    launch {
        // Exit: drop back below the stage on the same slow spatial spring.
        faceRise.animateTo(-1f, spatialSpec)
    }
    overlayReveal.animateTo(0f, effectsSlowSpec)
    backdropAlpha.animateTo(0f, effectsSlowSpec)
    faceRise.snapTo(-1f)
    faceScale.snapTo(AssistantOverlayTokens.FaceHiddenScale)
    faceAlpha.snapTo(1f)
    transcriptAlpha.snapTo(0f)
    overlayReveal.snapTo(0f)
}

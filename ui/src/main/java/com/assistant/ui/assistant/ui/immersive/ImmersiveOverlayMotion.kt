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

/**
 * Host teardown delay after FinishSession: island collapse to idle + slide-down.
 * Keep in sync with [AssistantOverlayTokens.IslandCollapseBeforeExitMs] (420) +
 * [AssistantOverlayTokens.IslandExitSlideMs] (520).
 */
const val ImmersiveExitTeardownDelayMs = 420L + 520L

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
 * Face: bottom → settled home (wake word + icon launch).
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
            faceScale.animateTo(1f, spatialSpec)
        }
        // Bottom → settled home (no peak overshoot).
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
    // Transcript / island already collapsed to idle before dismiss; fade any remainder.
    if (transcriptAlpha.value > 0.02f) {
        transcriptAlpha.animateTo(0f, effectsDefaultSpec)
    } else {
        transcriptAlpha.snapTo(0f)
    }
    launch {
        // Exit: drop the compact idle pill below the stage.
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

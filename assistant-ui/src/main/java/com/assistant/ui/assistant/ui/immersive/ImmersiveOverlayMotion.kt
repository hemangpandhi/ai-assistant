package com.assistant.ui.assistant.ui.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
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

internal suspend fun runImmersiveFullscreenEnter(
    backdropAlpha: Animatable<Float, AnimationVector1D>,
    faceRise: Animatable<Float, AnimationVector1D>,
    faceScale: Animatable<Float, AnimationVector1D>,
    faceAlpha: Animatable<Float, AnimationVector1D>,
    overlayReveal: Animatable<Float, AnimationVector1D>,
    origin: ImmersiveSummonOrigin,
) = coroutineScope {
    backdropAlpha.snapTo(1f)
    faceRise.snapTo(-1f)
    faceScale.snapTo(AssistantOverlayTokens.FaceStartScale)
    faceAlpha.snapTo(1f)
    val revealMs = if (origin == ImmersiveSummonOrigin.Icon) {
        AssistantOverlayTokens.IconRevealMs
    } else {
        AssistantOverlayTokens.HotwordRevealMs
    }
    try {
        launch {
            faceRise.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = AssistantOverlayTokens.FaceRiseMs
                    -1f at 0
                    1f at AssistantOverlayTokens.FaceRisePeakAtMs using FastOutSlowInEasing
                    0f at AssistantOverlayTokens.FaceRiseMs using FastOutSlowInEasing
                },
            )
        }
        launch {
            faceScale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = AssistantOverlayTokens.FaceRiseMs
                    AssistantOverlayTokens.FaceStartScale at 0
                    AssistantOverlayTokens.FacePeakScale at AssistantOverlayTokens.FaceRisePeakAtMs using FastOutSlowInEasing
                    1f at AssistantOverlayTokens.FaceRiseMs using FastOutSlowInEasing
                },
            )
        }
        overlayReveal.animateTo(
            1f,
            tween(revealMs, easing = FastOutSlowInEasing),
        )
    } finally {
        if (overlayReveal.value < 0.99f) overlayReveal.snapTo(1f)
        if (faceRise.value !in -0.02f..0.02f) faceRise.snapTo(0f)
        if (faceScale.value !in 0.98f..1.02f) faceScale.snapTo(1f)
    }
}

internal suspend fun fadeInImmersiveTranscript(
    transcriptAlpha: Animatable<Float, AnimationVector1D>,
) {
    delay(AssistantOverlayTokens.TranscriptFadeInDelayMs)
    transcriptAlpha.animateTo(
        1f,
        tween(AssistantOverlayTokens.TranscriptFadeInMs, easing = FastOutSlowInEasing),
    )
}

internal suspend fun runImmersiveExit(
    backdropAlpha: Animatable<Float, AnimationVector1D>,
    faceRise: Animatable<Float, AnimationVector1D>,
    faceScale: Animatable<Float, AnimationVector1D>,
    faceAlpha: Animatable<Float, AnimationVector1D>,
    transcriptAlpha: Animatable<Float, AnimationVector1D>,
    overlayReveal: Animatable<Float, AnimationVector1D>,
) = coroutineScope {
    transcriptAlpha.animateTo(0f, tween(AssistantOverlayTokens.TranscriptFadeOutMs))
    launch {
        faceRise.animateTo(
            -1f,
            tween(AssistantOverlayTokens.FaceExitMs, easing = FastOutSlowInEasing),
        )
    }
    overlayReveal.animateTo(
        0f,
        tween(AssistantOverlayTokens.OverlayExitMs, easing = FastOutSlowInEasing),
    )
    backdropAlpha.animateTo(
        0f,
        tween(AssistantOverlayTokens.BackdropExitMs, easing = FastOutSlowInEasing),
    )
    faceRise.snapTo(-1f)
    faceScale.snapTo(AssistantOverlayTokens.FaceHiddenScale)
    faceAlpha.snapTo(1f)
    transcriptAlpha.snapTo(0f)
    overlayReveal.snapTo(0f)
}

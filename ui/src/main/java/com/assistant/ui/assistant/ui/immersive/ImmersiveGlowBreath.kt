package com.assistant.ui.assistant.ui.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.assistant.ui.assistant.ui.theme.LocalAssistantIdleMotion

/**
 * Shared border/dock breath — same timing as [ImmersiveBorderGlow] so the face
 * stage and rim inhale/exhale together.
 */
data class ImmersiveGlowBreath(
    /** Raw breath scale (~1 at rest → ~1.65 idle / ~1.85 speech). */
    val scale: Float,
    /** 0 at rest → 1 near peak inhale. */
    val inhale: Float,
    /** Opacity multiplier used by the rim (and dock). */
    val fade: Float,
)

@Composable
fun rememberImmersiveGlowBreath(
    speechActive: Boolean = false,
    speechEnergy: Float = 0f,
): ImmersiveGlowBreath {
    val idleMotion = LocalAssistantIdleMotion.current
    val breathScale = remember { Animatable(1f) }
    LaunchedEffect(idleMotion, speechActive) {
        val breathEnabled = idleMotion || speechActive
        if (!breathEnabled) {
            breathScale.snapTo(1f)
            return@LaunchedEffect
        }
        val peak = if (speechActive) 1.85f else 1.65f
        val halfCycleMs = if (speechActive) 1_500 else 2_600
        while (true) {
            breathScale.animateTo(
                targetValue = peak,
                animationSpec = tween(durationMillis = halfCycleMs, easing = FastOutSlowInEasing),
            )
            breathScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = halfCycleMs, easing = FastOutSlowInEasing),
            )
        }
    }
    val energy = speechEnergy.coerceIn(0f, 1f)
    val breath = breathScale.value + energy * 0.22f
    val inhale = ((breath - 1f) / 0.85f).coerceIn(0f, 1f)
    val fade = 0.62f + inhale * 0.38f + energy * 0.12f
    return ImmersiveGlowBreath(scale = breath, inhale = inhale, fade = fade)
}

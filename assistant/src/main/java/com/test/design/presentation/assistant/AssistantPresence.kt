package com.test.design.presentation.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Quiet ambient presence — soft Google-like nebula, not a waveform.
 */
internal data class PresencePose(
    val energy: Float = 0.35f,
    val spread: Float = 0.5f,
    val sparkle: Float = 0.12f,
)

internal fun AssistantMood.toPresencePose(): PresencePose = when (this) {
    AssistantMood.Listening -> PresencePose(energy = 0.7f, spread = 0.62f, sparkle = 0.25f)
    AssistantMood.Speaking -> PresencePose(energy = 0.65f, spread = 0.58f, sparkle = 0.18f)
    AssistantMood.Thinking -> PresencePose(energy = 0.45f, spread = 0.48f, sparkle = 0.4f)
    AssistantMood.Searching -> PresencePose(energy = 0.6f, spread = 0.55f, sparkle = 0.45f)
    AssistantMood.Happy -> PresencePose(energy = 0.55f, spread = 0.6f, sparkle = 0.35f)
    AssistantMood.Sad -> PresencePose(energy = 0.25f, spread = 0.4f, sparkle = 0.08f)
    AssistantMood.Excited -> PresencePose(energy = 0.9f, spread = 0.72f, sparkle = 0.55f)
    AssistantMood.Bored -> PresencePose(energy = 0.2f, spread = 0.38f, sparkle = 0.05f)
    AssistantMood.Drowsy -> PresencePose(energy = 0.18f, spread = 0.36f, sparkle = 0.04f)
    AssistantMood.Tired -> PresencePose(energy = 0.16f, spread = 0.34f, sparkle = 0.03f)
    AssistantMood.Reading -> PresencePose(energy = 0.4f, spread = 0.48f, sparkle = 0.15f)
    AssistantMood.Idle -> PresencePose(energy = 0.32f, spread = 0.46f, sparkle = 0.1f)
}

private val PresenceSpring = spring<Float>(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessMediumLow,
)

private val OrbPalette = listOf(
    Color(0xFF8AB4F8),
    Color(0xFFC58AF9),
    Color(0xFF81C995),
)

@Composable
fun AssistantPresence(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
) {
    val target = mood.toPresencePose()
    val energy = remember { Animatable(target.energy) }
    val spread = remember { Animatable(target.spread) }
    val sparkle = remember { Animatable(target.sparkle) }
    val tint by animateColorAsState(
        targetValue = mood.glowColor,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "presence_tint",
    )

    LaunchedEffect(mood) {
        launch { energy.animateTo(target.energy, PresenceSpring) }
        launch { spread.animateTo(target.spread, PresenceSpring) }
        launch { sparkle.animateTo(target.sparkle, PresenceSpring) }
    }

    val infinite = rememberInfiniteTransition(label = "presence")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(5600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val breath by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Canvas(modifier = modifier) {
        drawAmbientPresence(
            phase = phase,
            breath = breath,
            energy = energy.value,
            spread = spread.value,
            sparkle = sparkle.value,
            tint = tint,
        )
    }
}

private fun DrawScope.drawAmbientPresence(
    phase: Float,
    breath: Float,
    energy: Float,
    spread: Float,
    sparkle: Float,
    tint: Color,
) {
    if (size.minDimension <= 0f) return
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val base = size.minDimension * 0.4f * spread * breath

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    tint.copy(alpha = 0.22f * energy),
                    tint.copy(alpha = 0.08f * energy),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = base * 1.55f,
            ),
            radius = base * 1.55f,
            center = Offset(cx, cy),
        )

    OrbPalette.forEachIndexed { i, color ->
        val ang = phase * (0.35f + i * 0.07f) + i * 1.5f
        val orbit = base * (0.26f + i * 0.05f)
        val ox = cx + cos(ang) * orbit * 0.45f
        val oy = cy + sin(ang * 0.85f) * orbit * 0.32f
        val r = base * (0.48f - i * 0.07f) * (0.82f + 0.18f * energy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.28f * energy),
                    color.copy(alpha = 0.08f * energy),
                    Color.Transparent,
                ),
                center = Offset(ox, oy),
                radius = r,
            ),
            radius = r,
            center = Offset(ox, oy),
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.18f * energy),
                tint.copy(alpha = 0.14f * energy),
                Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = base * 0.45f,
        ),
        radius = base * 0.45f,
        center = Offset(cx, cy),
    )

    if (sparkle > 0.12f) {
        for (i in 0 until 4) {
            val a = phase * 0.9f + i * (2f * PI.toFloat() / 4f)
            val dist = base * (0.58f + 0.16f * sin(phase + i).toFloat())
            val px = cx + cos(a) * dist
            val py = cy + sin(a * 1.05f) * dist * 0.65f
            val twinkle = 0.35f + 0.65f * ((sin(phase * 1.4f + i) + 1f) * 0.5f).toFloat()
            drawCircle(
                color = Color.White.copy(alpha = 0.22f * sparkle * twinkle),
                radius = 1.4f * twinkle,
                center = Offset(px, py),
            )
        }
    }
}

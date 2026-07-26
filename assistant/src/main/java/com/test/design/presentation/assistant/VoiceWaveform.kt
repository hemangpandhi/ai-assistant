package com.test.design.presentation.assistant

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Energy for the shared Siri-style waveform — visual stays identical; only amplitude/speed change.
 */
internal data class WavePose(
    val amplitude: Float = 0.35f,
    val speed: Float = 0.55f,
    val thickness: Float = 0.7f,
    val bloom: Float = 0.4f,
)

internal fun AssistantMood.toWavePose(): WavePose = when (this) {
    AssistantMood.Listening -> WavePose(amplitude = 0.95f, speed = 0.85f, thickness = 1f, bloom = 0.9f)
    AssistantMood.Speaking -> WavePose(amplitude = 1f, speed = 1.1f, thickness = 1.05f, bloom = 0.85f)
    AssistantMood.Thinking -> WavePose(amplitude = 0.55f, speed = 0.45f, thickness = 0.8f, bloom = 0.6f)
    AssistantMood.Searching -> WavePose(amplitude = 0.8f, speed = 1f, thickness = 0.9f, bloom = 0.75f)
    AssistantMood.Happy -> WavePose(amplitude = 0.7f, speed = 0.75f, thickness = 0.85f, bloom = 0.7f)
    AssistantMood.Sad -> WavePose(amplitude = 0.28f, speed = 0.35f, thickness = 0.55f, bloom = 0.3f)
    AssistantMood.Excited -> WavePose(amplitude = 1.1f, speed = 1.25f, thickness = 1.1f, bloom = 1f)
    AssistantMood.Bored -> WavePose(amplitude = 0.22f, speed = 0.28f, thickness = 0.5f, bloom = 0.22f)
    AssistantMood.Drowsy -> WavePose(amplitude = 0.18f, speed = 0.22f, thickness = 0.45f, bloom = 0.18f)
    AssistantMood.Tired -> WavePose(amplitude = 0.2f, speed = 0.25f, thickness = 0.48f, bloom = 0.2f)
    AssistantMood.Reading -> WavePose(amplitude = 0.4f, speed = 0.5f, thickness = 0.65f, bloom = 0.45f)
    AssistantMood.Idle -> WavePose(amplitude = 0.32f, speed = 0.4f, thickness = 0.6f, bloom = 0.35f)
}

private val WaveSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Cool blue-forward palette — cyan → indigo → soft violet. */
private val WaveLayers = listOf(
    WaveLayer(Color(0xFF82B1FF), 1.05f, 0.5f, 0.9f),
    WaveLayer(Color(0xFF40C4FF), 0.95f, 0.65f, 1.15f),
    WaveLayer(Color(0xFF7C4DFF), 0.82f, 0.55f, 0.8f),
    WaveLayer(Color(0xFF26C6DA), 0.72f, 0.7f, 1.05f),
    WaveLayer(Color(0xFF8AB4F8), 0.62f, 0.6f, 0.95f),
)

private data class WaveLayer(
    val color: Color,
    val ampScale: Float,
    val alpha: Float,
    val freq: Float,
)

/**
 * Colorful multi-layer organic waveform. Same look for every state — energy morphs.
 */
@Composable
fun VoiceWaveform(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    color: Color = mood.glowColor,
) {
    val target = mood.toWavePose()
    val amplitude = remember { Animatable(target.amplitude) }
    val speed = remember { Animatable(target.speed) }
    val thickness = remember { Animatable(target.thickness) }
    val bloom = remember { Animatable(target.bloom) }

    LaunchedEffect(mood) {
        launch { amplitude.animateTo(target.amplitude, WaveSpring) }
        launch { speed.animateTo(target.speed, WaveSpring) }
        launch { thickness.animateTo(target.thickness, WaveSpring) }
        launch { bloom.animateTo(target.bloom, WaveSpring) }
    }

    val infinite = rememberInfiniteTransition(label = "siri_wave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Suppress unused color param warning while keeping API stable for callers.
    @Suppress("UNUSED_VARIABLE")
    val ignoredAccent = color

    Canvas(modifier = modifier) {
        drawSiriWaveform(
            phase = phase * (0.55f + speed.value),
            amplitude = amplitude.value,
            thickness = thickness.value,
            bloom = bloom.value,
        )
    }
}

private fun DrawScope.drawSiriWaveform(
    phase: Float,
    amplitude: Float,
    thickness: Float,
    bloom: Float,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val midY = size.height * 0.5f
    val maxAmp = size.height * 0.42f * amplitude
    val steps = 96

    // Soft cool bloom behind the ribbons
    if (bloom > 0.05f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF40C4FF).copy(alpha = 0.22f * bloom),
                    Color(0xFF8AB4F8).copy(alpha = 0.12f * bloom),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.5f, midY),
                radius = size.width * 0.42f,
            ),
            radius = size.width * 0.42f,
            center = Offset(size.width * 0.5f, midY),
        )
    }

    // Colored organic ribbons (filled lobes above/below center)
    WaveLayers.forEachIndexed { index, layer ->
        val path = Path()
        val layerAmp = maxAmp * layer.ampScale
        val layerPhase = phase * layer.freq + index * 0.7f
        path.moveTo(0f, midY)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = size.width * t
            val envelope = sin(t * PI).toFloat().coerceAtLeast(0f)
            val y = midY - layerAmp * envelope *
                (0.55f + 0.45f * sin(t * PI * 3f * layer.freq + layerPhase).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val t = i / steps.toFloat()
            val x = size.width * t
            val envelope = sin(t * PI).toFloat().coerceAtLeast(0f)
            val y = midY + layerAmp * 0.85f * envelope *
                (0.5f + 0.5f * sin(t * PI * 2.6f * layer.freq - layerPhase * 0.8f + 1.2f).toFloat())
            path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path = path,
            color = layer.color.copy(alpha = layer.alpha * (0.45f + 0.55f * amplitude)),
            style = Fill,
        )
    }

    // Bright center spine
    val spine = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = size.width * t
        val envelope = sin(t * PI).toFloat()
        val y = midY + maxAmp * 0.08f * envelope * sin(t * 8f + phase * 1.4f).toFloat()
        if (i == 0) spine.moveTo(x, y) else spine.lineTo(x, y)
    }
    drawPath(
        spine,
        Color.White.copy(alpha = 0.85f),
        style = Stroke(
            width = (2.5f * thickness).coerceAtLeast(1.5f),
            cap = StrokeCap.Round,
        ),
    )
}

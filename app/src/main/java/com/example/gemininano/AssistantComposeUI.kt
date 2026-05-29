package com.example.gemininano

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------
// Design constants
// ---------------------------------------------

private object AssistantColors {
    val CardGlow = Color(0xFF001321)
    val CardTop = Color(0xCC080B16)
    val CardMid = Color(0xB300050F)
    val CardBottom = Color(0xB3000718)

    val BorderStart = Color(0x6600E5FF)
    val BorderEnd = Color(0x66FFC2FF)

    val WaveBgStart = Color(0x0000123C)
    val WaveBgMid = Color(0x6612346A)

    val WaveMainCyan = Color(0xFF00E5FF)
    val WaveMainBlue = Color(0xFF0050C8)

    val WaveMagentaSoft = Color(0x66F500FF)
    val WaveMagentaStrong = Color(0x99DE00FF)

    val TextSecondary = Color(0xDDFFFFFF)
}

enum class SystemPhase {
    LISTENING,
    PROCESSING,
    EXECUTING,
    STANDBY
}

// ---------------------------------------------
// Core assistant UI (glass card + wave)
// ---------------------------------------------

@Composable
fun PureComposeCarAssistant(
    modifier: Modifier = Modifier,
    systemPhase: SystemPhase = SystemPhase.STANDBY,
    energy: Float = 0.2f
) {
    val (stateTitle, stateSubtitle) = when (systemPhase) {
        SystemPhase.LISTENING -> "How can I help?" to "Listening for your request"
        SystemPhase.PROCESSING -> "Thinking…" to "Analyzing your request"
        SystemPhase.EXECUTING -> "Speaking" to "Responding to your request"
        SystemPhase.STANDBY -> "Ready when you are." to "Assistant idle"
    }

    val brandFont = FontFamily.SansSerif

    // Very subtle scale "breath" tied to energy
    val scale = 1f + ((energy - 0.9f).coerceIn(-0.2f, 0.4f)) * 0.025f

    Box(
        modifier = modifier
            .graphicsLayer {
                shape = RoundedCornerShape(32.dp)
                clip = true
                alpha = 0.96f
                shadowElevation = 24f
                scaleX = scale
                scaleY = scale
            }
    ) {
        // Stronger, darker cyan‑navy glow behind the card (blurred background only)
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(40.dp)
                .background(
                    AssistantColors.CardGlow.copy(
                        alpha = 0.55f + (energy * 0.25f).coerceIn(0f, 1f)
                    )
                )
        )

        // Glass gradient with its own blur
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(18.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AssistantColors.CardTop,
                            AssistantColors.CardMid,
                            AssistantColors.CardBottom
                        )
                    )
                )
        )

        // Gradient border around card
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 0.2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            AssistantColors.BorderStart,
                            AssistantColors.BorderEnd,
                            AssistantColors.BorderStart
                        )
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
        )

        // Subtle top-edge highlight
        Box(
            modifier = Modifier
                .matchParentSize()
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
            ) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = size.height
                )
            }
        }

        // Foreground content (text + waveform)
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Crossfade(
                targetState = stateSubtitle,
                label = "assistant_phase_subtitle"
            ) { subtitle ->
                Text(
                    text = subtitle,
                    style = TextStyle(
                        color = AssistantColors.TextSecondary,
                        fontSize = 13.sp,
                        letterSpacing = 0.3.sp,
                        fontFamily = brandFont
                    )
                )
            }

            VoicePlate(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                energy = energy
            )
        }
    }
}

// ---------------------------------------------
// Voice plate (container around the wave)
// ---------------------------------------------

@Composable
private fun VoicePlate(
    modifier: Modifier = Modifier,
    energy: Float
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        // blurred inner glass glow
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(20.dp)
                .background(Color.Transparent)
        )

        CustomAssistantWave(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            energy = energy
        )
    }
}

// ---------------------------------------------
// Custom / Siri‑inspired voice ribbon
// ---------------------------------------------

@Composable
fun CustomAssistantWave(
    modifier: Modifier = Modifier,
    energy: Float = 2f
) {
    val infinite = rememberInfiniteTransition(label = "Custom_wave_dense")

    val mainPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing)
        ),
        label = "Custom_phase_main"
    )

    val detailPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing)
        ),
        label = "Custom_phase_detail"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
        ) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f

            val verticalPadding = 1.dp.toPx()
            val usableHeight = (h - verticalPadding * 2f).coerceAtLeast(0f)
            val maxAmplitude = usableHeight / 2f

            val clampedEnergy = energy.coerceIn(0.2f, 1.5f)
            val energyNorm = ((clampedEnergy - 0.2f) / (1.5f - 0.2f)).coerceIn(0f, 1f)

            val maxBaseForNoClip = maxAmplitude / 0.9f
            val baseAmplitude = maxBaseForNoClip * (0.75f + 0.20f * energyNorm)
            val frequencyBase = 1.8f + 1.2f * clampedEnergy

            drawLine(
                color = Color(0x55FFFFFF),
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 1.dp.toPx()
            )

            val segments = 150 // Optimized from 360 for performance
            val stepX = w / segments

            fun buildDenseRibbonPath(
                amplitude: Float,
                topThicknessScale: Float,
                bottomThicknessScale: Float,
                freq: Float,
                phase1: Float,
                phase2: Float
            ): Path {
                val path = Path()

                for (i in 0..segments) {
                    val x = i * stepX
                    val t = i.toFloat() / segments
                    val envelope = sin(PI * t).toFloat().pow(1.2f)

                    val base = sin(2f * PI.toFloat() * freq * t + phase1)
                    val harm1 = 0.50f * sin(2f * PI.toFloat() * (freq * 2.3f) * t - phase2 * 1.2f)
                    val harm2 = 0.35f * sin(2f * PI.toFloat() * (freq * 4.1f) * t + phase1 * 1.7f)
                    val harm3 = 0.15f * sin(2f * PI.toFloat() * (freq * 7.4f) * t - phase2 * 2.5f)

                    val totalWave = (base + harm1 + harm2 + harm3) / (1f + 0.50f + 0.35f + 0.15f)
                    val y = centerY - (totalWave * amplitude * envelope * topThicknessScale)

                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                for (i in segments downTo 0) {
                    val x = i * stepX
                    val t = i.toFloat() / segments
                    val envelope = sin(PI * t).toFloat().pow(1.2f)

                    val base = sin(2f * PI.toFloat() * freq * t + phase1)
                    val harm1 = 0.50f * sin(2f * PI.toFloat() * (freq * 2.3f) * t - phase2 * 1.2f)
                    val harm2 = 0.35f * sin(2f * PI.toFloat() * (freq * 4.1f) * t + phase1 * 1.7f)
                    val harm3 = 0.15f * sin(2f * PI.toFloat() * (freq * 7.4f) * t - phase2 * 2.5f)

                    val totalWave = (base + harm1 + harm2 + harm3) / (1f + 0.50f + 0.35f + 0.15f)
                    val y = centerY + (totalWave * amplitude * envelope * bottomThicknessScale)

                    path.lineTo(x, y)
                }

                path.close()
                return path
            }

            // LAYER 1: Background glow ribbon
            val bgRibbon = buildDenseRibbonPath(baseAmplitude * 1.1f, 0.6f, 0.6f, frequencyBase * 0.75f, -mainPhase * 0.5f, detailPhase * 0.4f)
            drawPath(bgRibbon, Brush.horizontalGradient(listOf(AssistantColors.WaveBgStart, AssistantColors.WaveBgMid, AssistantColors.WaveBgMid, AssistantColors.WaveBgMid, AssistantColors.WaveBgStart)), alpha = 0.7f)

            // LAYER 2: Main cyan ribbon
            val mainRibbon = buildDenseRibbonPath(baseAmplitude * 1.0f, 0.9f, 0.5f, frequencyBase, mainPhase, detailPhase)
            drawPath(mainRibbon, Brush.horizontalGradient(listOf(Color(0x0000185A), AssistantColors.WaveMainCyan, AssistantColors.WaveMainBlue, AssistantColors.WaveMainCyan, Color(0x0000185A))), alpha = 1.0f)

            // LAYER 3: Magenta contrast ribbon
            val contrastRibbon = buildDenseRibbonPath(baseAmplitude * 0.9f, 0.35f, 0.75f, frequencyBase * 1.3f, -detailPhase * 1.1f, mainPhase * 0.8f)
            drawPath(contrastRibbon, Brush.horizontalGradient(listOf(Color(0x000F0020), AssistantColors.WaveMagentaSoft, AssistantColors.WaveMagentaStrong, AssistantColors.WaveMagentaSoft, Color(0x000F0020))), alpha = 0.75f)

            // LAYER 4: Core highlight line
            val coreLinePath = Path()
            for (i in 0..segments) {
                val x = i * stepX
                val t = i.toFloat() / segments
                val envelope = sin(PI * t).toFloat().pow(1.4f)
                val base = sin(2f * PI.toFloat() * (frequencyBase * 1.1f) * t + mainPhase * 1.2f)
                val harm1 = 0.50f * sin(2f * PI.toFloat() * (frequencyBase * 2.5f) * t - detailPhase * 1.5f)
                val harm2 = 0.25f * sin(2f * PI.toFloat() * (frequencyBase * 5.2f) * t + mainPhase * 2.0f)
                val totalWave = (base + harm1 + harm2) / (1f + 0.50f + 0.25f)
                val y = centerY + (totalWave * baseAmplitude * 0.27f * envelope)
                if (i == 0) coreLinePath.moveTo(x, y) else coreLinePath.lineTo(x, y)
            }
            drawPath(coreLinePath, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round), alpha = 0.95f)

            // LAYER 5: Ghost filament
            val ghostLinePath = Path()
            for (i in 0..segments) {
                val x = i * stepX
                val t = i.toFloat() / segments
                val envelope = sin(PI * t).toFloat().pow(1.6f)
                val base = sin(2f * PI.toFloat() * (frequencyBase * 1.4f) * t - detailPhase * 0.9f)
                val harm1 = 0.40f * sin(2f * PI.toFloat() * (frequencyBase * 3.3f) * t + mainPhase * 1.4f)
                val totalWave = (base + harm1) / (1f + 0.40f)
                val y = centerY - (totalWave * baseAmplitude * 0.45f * envelope)
                if (i == 0) ghostLinePath.moveTo(x, y) else ghostLinePath.lineTo(x, y)
            }
            drawPath(ghostLinePath, Color(0xFF00F2FE), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round), alpha = 0.7f)
        }
    }
}

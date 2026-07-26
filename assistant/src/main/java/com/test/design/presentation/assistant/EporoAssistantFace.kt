package com.test.design.presentation.assistant

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.graphics.shapes.Morph
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

val EporoShell = Color(0xFF1C1D21)
val EporoShellShade = Color(0xFF121316)
val EporoVisor = Color.Black
val EporoShellRim = Color(0xFF5A5C64)
val EporoGlow = Color(0xFF9A7DFF)
val EporoGlowSoft = Color(0xFFB8A6FF)

/**
 * EPORO / robot head — Compose Canvas architecture:
 * HeadShell (SemiCircle) → Visor → Eyes.
 * Same outer footprint as [DroidAssistantFace].
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
fun EporoAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    shellColor: Color = EporoShell,
    visorColor: Color = EporoVisor,
    glowColor: Color = EporoGlow,
) {
    val pose = mood.toEporoPose()
    // Fixed SemiCircle dark-gray outer plate.
    val shellMorph = remember {
        ExpressiveShellMorphState(
            morph = Morph(
                start = ExpressiveShellKind.SemiCircle.toRoundedPolygon(),
                end = ExpressiveShellKind.SemiCircle.toRoundedPolygon(),
            ),
            progress = 1f,
        )
    }
    val eyeOpen = remember { Animatable(pose.eyeOpen) }
    val eyeGap = remember { Animatable(pose.eyeGap) }
    val lookX = remember { Animatable(pose.lookX) }
    val lookY = remember { Animatable(pose.lookY) }
    val tilt = remember { Animatable(pose.tilt) }
    val blink = remember { Animatable(1f) }
    val currentMood by rememberUpdatedState(mood)

    LaunchedEffect(mood) {
        val p = mood.toEporoPose()
        launch { eyeOpen.animateTo(p.eyeOpen, spring(dampingRatio = 0.72f)) }
        launch { eyeGap.animateTo(p.eyeGap, spring(dampingRatio = 0.72f)) }
        launch { lookX.animateTo(p.lookX, spring(dampingRatio = 0.78f)) }
        launch { lookY.animateTo(p.lookY, spring(dampingRatio = 0.78f)) }
        launch { tilt.animateTo(p.tilt, spring(dampingRatio = 0.8f)) }
    }

    // Occasional blink. Keyed on Unit so dialogue mood hops don't reset the timer
    // (LaunchedEffect(mood) was cancelling before the long gap elapsed).
    LaunchedEffect(Unit) {
        delay(Random.nextLong(1_200, 2_400))
        while (isActive) {
            val closeTo = when (currentMood) {
                AssistantMood.Drowsy, AssistantMood.Tired -> 0.08f
                else -> 0.05f
            }
            blink.snapTo(1f)
            blink.animateTo(closeTo, tween(90))
            delay(55)
            blink.animateTo(1f, tween(150))
            if (Random.nextFloat() < 0.28f) {
                delay(100)
                blink.animateTo(closeTo, tween(75))
                delay(40)
                blink.animateTo(1f, tween(140))
            }
            delay(Random.nextLong(2_800, 5_500))
        }
    }

    val life = rememberInfiniteTransition(label = "eporo_breath")
    val glowPhase by life.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mood) {
                    AssistantMood.Listening, AssistantMood.Speaking -> 900
                    AssistantMood.Searching, AssistantMood.Thinking -> 1_400
                    else -> 2_200
                },
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eporo_glow",
    )
    val scan by life.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eporo_scan",
    )
    // Whole-head breath + idle float — mirrors ImmersiveEyesFace presence.
    val shellBreath by life.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eporo_shell_breath",
    )
    val idleBob by life.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eporo_idle_bob",
    )
    val idleSway by life.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eporo_idle_sway",
    )

    // Same square footprint as ImmersiveEyesFace.
    val blinkOpen = blink.value.coerceIn(0.05f, 1.2f)
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                val s = shellBreath
                scaleX = s
                scaleY = s
                translationX = idleSway * 2.8f
                translationY = idleBob * 4.2f
            },
    ) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        // Fit EPORO shell into Immersive eyes bounds (same height/size).
        val match = immersiveMatchedShellBounds(w, h)
        val sx = match.width / w
        val sy = match.height / h

        translate(left = match.left, top = match.top) {
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                rotate(degrees = tilt.value, pivot = Offset(cx, h * 0.48f)) {
                    drawHead(shellMorph = shellMorph, shellColor = shellColor)
                    drawVisor(visorColor)

                    val gap = 0.24f * eyeGap.value
                    val eyeY = h * (0.50f + lookY.value * 0.03f)
                    val gaze = lookX.value + if (
                        mood == AssistantMood.Searching || mood == AssistantMood.Reading
                    ) {
                        scan * 0.04f
                    } else {
                        0f
                    }
                    val pulse = (0.9f + 0.1f * glowPhase).coerceIn(0.85f, 1f)
                    val eyeR = (minOf(w, h) * 0.095f) * eyeOpen.value * pulse

                    drawEye(
                        center = Offset(w * (0.50f - gap) + gaze * eyeR, eyeY),
                        radius = eyeR,
                        glow = glowColor,
                        open = blinkOpen,
                    )
                    drawEye(
                        center = Offset(w * (0.50f + gap) + gaze * eyeR, eyeY),
                        radius = eyeR,
                        glow = glowColor,
                        open = blinkOpen,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawHead(
    shellMorph: ExpressiveShellMorphState,
    shellColor: Color,
) {
    val w = size.width
    val h = size.height
    // Leave a hairline margin so the rim stroke is not clipped by the canvas edge.
    val pad = minOf(w, h) * 0.018f
    val bounds = Rect(
        left = pad,
        top = pad,
        right = w - pad,
        bottom = h - pad,
    )
    drawExpressiveFaceShell(
        morphState = shellMorph,
        bounds = bounds,
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF26282C),
                shellColor,
                EporoShellShade,
            ),
            center = Offset(w * 0.5f, h * 0.15f),
            radius = w,
        ),
    )
    // Subtle rim — separates the dark frame from the immersive backdrop.
    drawExpressiveFaceShell(
        morphState = shellMorph,
        bounds = bounds,
        color = EporoShellRim.copy(alpha = 0.55f),
        style = Stroke(width = 0.022f, cap = StrokeCap.Round),
    )
}

/** Organic Bézier visor — convex brow, blunt chin tab.
 * Full-black glass inset in the dark-gray SemiCircle bezel.
 */
private fun DrawScope.drawVisor(visorColor: Color) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        // ~18–20% inset from SemiCircle shell for a clear frame rim.
        moveTo(w * 0.18f, h * 0.34f)
        cubicTo(
            w * 0.26f, h * 0.22f,
            w * 0.74f, h * 0.22f,
            w * 0.82f, h * 0.34f,
        )
        cubicTo(
            w * 0.88f, h * 0.44f,
            w * 0.86f, h * 0.62f,
            w * 0.76f, h * 0.68f,
        )
        cubicTo(
            w * 0.66f, h * 0.73f,
            w * 0.58f, h * 0.76f,
            w * 0.50f, h * 0.76f,
        )
        cubicTo(
            w * 0.42f, h * 0.76f,
            w * 0.34f, h * 0.73f,
            w * 0.24f, h * 0.68f,
        )
        cubicTo(
            w * 0.14f, h * 0.62f,
            w * 0.12f, h * 0.44f,
            w * 0.18f, h * 0.34f,
        )
        close()
    }
    drawPath(path = p, color = visorColor)
    // Very soft glass sheen — keeps the visor reading as full black.
    drawPath(
        path = p,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.06f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.45f),
            ),
            startY = h * 0.22f,
            endY = h * 0.76f,
        ),
    )
    // Elongated top specular.
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(w * 0.44f, h * 0.28f),
        size = Size(w * 0.12f, h * 0.024f),
        cornerRadius = CornerRadius(h * 0.02f, h * 0.02f),
    )
}

private fun DrawScope.drawEye(
    center: Offset,
    radius: Float,
    glow: Color,
    open: Float = 1f,
) {
    // Vertical squash reads as an eyelid blink; bloom softens with openness.
    scale(scaleX = 1f, scaleY = open, pivot = center) {
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = glow.copy(alpha = 0.45f * open.coerceIn(0.2f, 1f)).toArgb()
                maskFilter = BlurMaskFilter(radius * 0.9f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawCircle(center.x, center.y, radius * 1.55f, paint)
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = radius * 1.7f,
            ),
            radius = radius * 1.7f,
            center = center,
        )
        drawCircle(
            color = glow.copy(alpha = 0.98f),
            radius = radius,
            center = center,
            style = Stroke(width = radius * 0.32f, cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color.Black,
            radius = radius * 0.58f,
            center = center,
        )
        // Specular on bottom of ring.
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = radius * 0.1f,
            center = Offset(center.x, center.y + radius * 0.78f),
        )
    }
}

internal data class EporoPose(
    val eyeOpen: Float = 1f,
    val eyeGap: Float = 1f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val ringPulse: Float = 0.75f,
    val tilt: Float = 0f,
)

internal fun AssistantMood.toEporoPose(): EporoPose = when (this) {
    AssistantMood.Idle -> EporoPose(eyeOpen = 1f, ringPulse = 0.65f)
    AssistantMood.Listening -> EporoPose(
        eyeOpen = 1.05f,
        eyeGap = 1.06f,
        ringPulse = 1f,
        lookY = -0.03f,
    )
    AssistantMood.Thinking -> EporoPose(
        eyeOpen = 0.94f,
        lookX = 0.18f,
        lookY = -0.08f,
        tilt = 2f,
    )
    AssistantMood.Reading -> EporoPose(
        eyeOpen = 0.96f,
        lookX = 0.14f,
        lookY = 0.1f,
    )
    AssistantMood.Searching -> EporoPose(
        eyeOpen = 1.04f,
        eyeGap = 1.08f,
        ringPulse = 0.9f,
    )
    AssistantMood.Speaking -> EporoPose(
        eyeOpen = 1.03f,
        ringPulse = 1f,
        lookY = 0.02f,
    )
    AssistantMood.Happy, AssistantMood.Excited -> EporoPose(
        eyeOpen = 1.08f,
        eyeGap = 1.04f,
        ringPulse = 0.95f,
        tilt = -2f,
    )
    AssistantMood.Sad -> EporoPose(
        eyeOpen = 0.8f,
        lookY = 0.14f,
        ringPulse = 0.45f,
        tilt = 3f,
    )
    AssistantMood.Bored -> EporoPose(
        eyeOpen = 0.72f,
        lookX = 0.28f,
        ringPulse = 0.4f,
    )
    AssistantMood.Drowsy, AssistantMood.Tired -> EporoPose(
        eyeOpen = 0.45f,
        lookY = 0.08f,
        ringPulse = 0.35f,
        tilt = 4f,
    )
}

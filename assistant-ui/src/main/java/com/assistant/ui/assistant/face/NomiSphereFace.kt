package com.assistant.ui.assistant.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Lightweight NIO NOMI–inspired orb: matte metal shell, recessed glossy glass face,
 * and small Compose [graphicsLayer] pan/tilt (no OpenGL / Filament).
 *
 * Cost profile: one Canvas pass + one hardware layer transform + a few springs.
 */
@Composable
fun NomiSphereFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
) {
    val target = mood.toFacePose()
    val eyeOpen = remember { Animatable(target.eyeOpen) }
    val eyeWidth = remember { Animatable(target.eyeWidth) }
    val eyeHeight = remember { Animatable(target.eyeHeight) }
    val eyeGap = remember { Animatable(target.eyeGap) }
    val tilt = remember { Animatable(target.tilt) }
    val lookX = remember { Animatable(target.lookX) }
    val lookY = remember { Animatable(target.lookY) }
    val mouthCurve = remember { Animatable(target.mouthCurve) }
    val mouthOpen = remember { Animatable(target.mouthOpen) }
    val eyeStyle = remember { Animatable(target.eyeStyle) }
    val blink = remember { Animatable(1f) }

    val poseSpring = remember {
        spring<Float>(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow,
        )
    }

    LaunchedEffect(mood, gazeX, gazeY) {
        val lx = gazeX ?: target.lookX
        val ly = gazeY ?: target.lookY
        launch { eyeOpen.animateTo(target.eyeOpen, poseSpring) }
        launch { eyeWidth.animateTo(target.eyeWidth, poseSpring) }
        launch { eyeHeight.animateTo(target.eyeHeight, poseSpring) }
        launch { eyeGap.animateTo(target.eyeGap, poseSpring) }
        launch { tilt.animateTo(target.tilt, poseSpring) }
        launch { eyeStyle.animateTo(target.eyeStyle, poseSpring) }
        launch { mouthCurve.animateTo(target.mouthCurve, poseSpring) }
        if (mood != AssistantMood.Speaking) {
            launch { mouthOpen.animateTo(target.mouthOpen, poseSpring) }
        }
        launch { lookX.animateTo(lx.coerceIn(-1f, 1f), poseSpring) }
        launch { lookY.animateTo(ly.coerceIn(-1f, 1f), poseSpring) }
    }

    LaunchedEffect(mood) {
        if (mood != AssistantMood.Speaking) return@LaunchedEffect
        while (isActive) {
            mouthOpen.animateTo(
                Random.nextFloat() * 0.28f + 0.28f,
                tween(Random.nextInt(90, 150)),
            )
            mouthOpen.animateTo(
                Random.nextFloat() * 0.08f + 0.03f,
                tween(Random.nextInt(70, 120)),
            )
        }
    }

    LaunchedEffect(mood) {
        while (isActive) {
            delay(Random.nextLong(2400, 4600))
            if (eyeStyle.value > 0.6f) continue
            blink.animateTo(0.1f, tween(65))
            delay(35)
            blink.animateTo(1f, tween(110))
            if (Random.nextFloat() < 0.22f) {
                delay(80)
                blink.animateTo(0.1f, tween(50))
                delay(28)
                blink.animateTo(1f, tween(100))
            }
        }
    }

    // One slow breath only — avoids stacked infinite transitions.
    val breath by rememberInfiniteTransition(label = "nomi_sphere_breath").animateFloat(
        initialValue = 0.992f,
        targetValue = 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    val lx = lookX.value.coerceIn(-1f, 1f)
    val ly = lookY.value.coerceIn(-1f, 1f)

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                // Fake ball-socket pan/tilt via Compose hardware layer (cheap).
                rotationY = lx * 14f
                rotationX = (-ly * 11f) + (tilt.value * 0.35f)
                cameraDistance = 18f * density
                scaleX = breath
                scaleY = breath
                translationX = lx * 2.5f
                translationY = ly * 1.8f
            },
    ) {
        val side = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val shellR = side * 0.42f
        val faceR = shellR * 0.86f
        val bezelR = shellR * 0.92f

        // Socket contact shadow (grounds the orb in its well).
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.42f),
                    Color.Black.copy(alpha = 0.12f),
                    Color.Transparent,
                ),
                center = Offset(cx + lx * shellR * 0.04f, cy + shellR * 0.78f),
                radius = shellR * 0.72f,
            ),
            topLeft = Offset(cx - shellR * 0.72f, cy + shellR * 0.52f),
            size = Size(shellR * 1.44f, shellR * 0.42f),
        )

        // Matte metallic body — soft volume, no hard specular on the shell.
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFF9AA3B0),
                    0.35f to Color(0xFF6A7380),
                    0.72f to Color(0xFF3A414C),
                    1.0f to Color(0xFF1C2128),
                ),
                center = Offset(cx - shellR * 0.28f, cy - shellR * 0.34f),
                radius = shellR * 1.35f,
            ),
            radius = shellR,
            center = Offset(cx, cy),
        )
        // Satin rim catch-light (matte, not glass).
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.28f),
                ),
                start = Offset(cx - shellR, cy - shellR),
                end = Offset(cx + shellR, cy + shellR),
            ),
            radius = shellR,
            center = Offset(cx, cy),
        )

        // Recessed bezel between shell and glass face.
        drawCircle(
            color = Color(0xFF0A0B0E),
            radius = bezelR,
            center = Offset(cx, cy),
        )
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF2A2E36),
                    Color(0xFF050608),
                    Color(0xFF1A1D24),
                ),
                start = Offset(cx - bezelR, cy - bezelR),
                end = Offset(cx + bezelR, cy + bezelR),
            ),
            radius = bezelR,
            center = Offset(cx, cy),
            style = Stroke(width = shellR * 0.035f),
        )

        // OLED-black face disk with slight volume.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF12141A),
                    Color(0xFF050508),
                    Color(0xFF000000),
                ),
                center = Offset(cx - faceR * 0.12f, cy - faceR * 0.18f),
                radius = faceR * 1.2f,
            ),
            radius = faceR,
            center = Offset(cx, cy),
        )

        val open = (eyeOpen.value * blink.value).coerceIn(0.08f, 1.25f)
        val eW = faceR * 0.07f * eyeWidth.value
        val eH = faceR * 0.19f * eyeHeight.value * open
        val gap = faceR * 0.33f * eyeGap.value.coerceAtLeast(1.2f)
        // Eyes parallax opposite body look a bit — under-glass depth cue.
        val gazePx = lx * faceR * 0.05f
        val gazePy = ly * faceR * 0.04f
        val eyeY = cy - faceR * 0.05f + gazePy
        val left = Offset(cx - gap + gazePx, eyeY)
        val right = Offset(cx + gap + gazePx, eyeY)
        val glyph = Color(0xFFF5F7FA)

        drawSphereEye(left, eW, eH, eyeStyle.value, glyph)
        drawSphereEye(right, eW, eH, eyeStyle.value, glyph)

        // Quiet mouth — Nomi is mostly eyes; keep it for speak/mood only.
        if (abs(mouthCurve.value) > 0.15f || mouthOpen.value > 0.04f) {
            drawSphereMouth(
                center = Offset(cx + gazePx * 0.4f, cy + faceR * 0.32f + gazePy * 0.5f),
                faceR = faceR,
                curve = mouthCurve.value,
                open = mouthOpen.value,
                color = glyph,
            )
        }

        // Glass veil: slight dark film so glyphs sit under the cover.
        drawCircle(
            color = Color.Black.copy(alpha = 0.14f),
            radius = faceR,
            center = Offset(cx, cy),
        )

        // Soft fill highlight (glass curvature).
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = Offset(
                    cx - faceR * 0.32f + lx * faceR * 0.06f,
                    cy - faceR * 0.42f + ly * faceR * 0.05f,
                ),
                radius = faceR * 0.55f,
            ),
            radius = faceR * 0.55f,
            center = Offset(
                cx - faceR * 0.32f + lx * faceR * 0.06f,
                cy - faceR * 0.42f + ly * faceR * 0.05f,
            ),
        )

        // Sharp crescent specular — the main gloss cue.
        drawGlassCrescent(
            center = Offset(cx, cy),
            radius = faceR,
            lookX = lx,
            lookY = ly,
        )

        // Faint environment streak that drifts with look.
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                start = Offset(
                    cx - faceR * 0.55f + lx * faceR * 0.08f,
                    cy - faceR * 0.2f,
                ),
                end = Offset(
                    cx + faceR * 0.15f + lx * faceR * 0.08f,
                    cy + faceR * 0.55f,
                ),
            ),
            radius = faceR,
            center = Offset(cx, cy),
        )
    }
}

private fun DrawScope.drawGlassCrescent(
    center: Offset,
    radius: Float,
    lookX: Float,
    lookY: Float,
) {
    val cx = center.x - radius * 0.18f + lookX * radius * 0.05f
    val cy = center.y - radius * 0.28f + lookY * radius * 0.04f
    val path = Path().apply {
        // Thin arc along the upper-left glass edge.
        val r = radius * 0.82f
        val start = Offset(cx - r * 0.55f, cy - r * 0.05f)
        val mid = Offset(cx - r * 0.15f, cy - r * 0.55f)
        val end = Offset(cx + r * 0.35f, cy - r * 0.42f)
        moveTo(start.x, start.y)
        quadraticTo(mid.x, mid.y, end.x, end.y)
    }
    drawPath(
        path,
        color = Color.White.copy(alpha = 0.55f),
        style = Stroke(width = radius * 0.045f, cap = StrokeCap.Round),
    )
    drawPath(
        path,
        color = Color.White.copy(alpha = 0.22f),
        style = Stroke(width = radius * 0.09f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawSphereEye(
    center: Offset,
    width: Float,
    height: Float,
    style: Float,
    color: Color,
) {
    when {
        style > 0.35f -> {
            val path = Path().apply {
                moveTo(center.x - width * 1.15f, center.y + height * 0.15f)
                quadraticTo(
                    center.x,
                    center.y - height * (0.55f + 0.45f * style),
                    center.x + width * 1.15f,
                    center.y + height * 0.15f,
                )
            }
            drawPath(
                path,
                color,
                style = Stroke(width = width * 0.85f, cap = StrokeCap.Round),
            )
        }
        style < -0.25f -> {
            val w = width * 1.35f
            val flatten = (-style).coerceIn(0.25f, 1f)
            val h = (height * (1f - 0.75f * flatten)).coerceAtLeast(width * 0.35f)
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - w, center.y - h * 0.5f),
                size = Size(w * 2f, h),
                cornerRadius = CornerRadius(h, h),
            )
        }
        else -> {
            val w = width
            val h = height.coerceAtLeast(w * 1.1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - w, center.y - h),
                size = Size(w * 2f, h * 2f),
                cornerRadius = CornerRadius(w, w),
            )
            // Soft emissive catch inside the capsule (under glass).
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(center.x - w * 0.55f, center.y - h * 0.72f),
                size = Size(w * 0.7f, h * 0.5f),
                cornerRadius = CornerRadius(w * 0.4f, w * 0.4f),
            )
        }
    }
}

private fun DrawScope.drawSphereMouth(
    center: Offset,
    faceR: Float,
    curve: Float,
    open: Float,
    color: Color,
) {
    val halfW = faceR * 0.14f
    val smile = faceR * 0.06f * curve
    val openH = faceR * 0.06f * open.coerceIn(0f, 1f)

    if (openH > faceR * 0.012f) {
        val w = halfW * 1.15f
        val h = openH * 1.25f
        drawRoundRect(
            color = color.copy(alpha = 0.92f),
            topLeft = Offset(center.x - w * 0.5f, center.y - h * 0.3f),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.5f, h * 0.5f),
        )
    } else if (abs(curve) > 0.12f) {
        val path = Path().apply {
            moveTo(center.x - halfW, center.y)
            quadraticTo(center.x, center.y + smile, center.x + halfW, center.y)
        }
        drawPath(
            path,
            color.copy(alpha = 0.88f),
            style = Stroke(width = faceR * 0.03f, cap = StrokeCap.Round),
        )
    }
}

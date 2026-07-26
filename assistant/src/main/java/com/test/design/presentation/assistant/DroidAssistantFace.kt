package com.test.design.presentation.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dark Bugdroid shell + neon glyph (icon pack). */
val DroidShell = Color(0xFF0B1F33)
val DroidGlyph = Color(0xFFB8F818)
val DroidGreen = DroidGlyph

/**
 * Flat Android Bugdroid head — Material expressive shell + neon glyph from the icon pack.
 */
@Composable
fun DroidAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    bodyColor: Color = DroidShell,
    glyphColor: Color = DroidGlyph,
) {
    DroidAssistantFace(
        glyph = mood.toDroidFaceGlyph(),
        modifier = modifier,
        bodyColor = bodyColor,
        glyphColor = glyphColor,
        shellMood = mood,
    )
}

@Composable
fun DroidAssistantFace(
    glyph: DroidFaceGlyph,
    modifier: Modifier = Modifier,
    bodyColor: Color = DroidShell,
    glyphColor: Color = DroidGlyph,
    shellMood: AssistantMood = AssistantMood.Idle,
) {
    val shellMorph = rememberExpressiveShellMorph(shellMood)
    var fromGlyph by remember { mutableStateOf(glyph) }
    var toGlyph by remember { mutableStateOf(glyph) }
    val glyphProgress = remember { Animatable(1f) }

    LaunchedEffect(glyph) {
        if (glyph == toGlyph && glyphProgress.value == 1f) return@LaunchedEffect
        fromGlyph = toGlyph
        toGlyph = glyph
        glyphProgress.snapTo(0f)
        glyphProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        )
        fromGlyph = glyph
    }

    val progress = glyphProgress.value
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val side = min(size.width, size.height)
        val cx = size.width * 0.5f
        val headR = side * 0.42f
        // Flat chin near bottom — leave padding so stroke mouths stay inside the dome.
        val chinY = size.height * 0.90f
        val faceCy = chinY - headR * 0.48f
        val shellR = headR * 1.08f
        val shellBounds = Rect(
            left = cx - shellR,
            top = faceCy - shellR,
            right = cx + shellR,
            bottom = faceCy + shellR,
        )

        drawDroidAntennae(cx = cx, chinY = chinY, headR = headR, color = bodyColor)
        drawExpressiveFaceShell(
            morphState = shellMorph,
            bounds = shellBounds,
            color = bodyColor,
        )
        // Cross-morph glyphs: outgoing shrinks/fades while incoming grows in.
        if (progress < 1f && fromGlyph != toGlyph) {
            val outScale = 1f - 0.18f * progress
            val inScale = 0.82f + 0.18f * progress
            val pivot = Offset(cx, faceCy)
            withTransform({
                scale(scaleX = outScale, scaleY = outScale, pivot = pivot)
            }) {
                drawDroidGlyph(
                    glyph = fromGlyph,
                    cx = cx,
                    chinY = chinY,
                    headR = headR,
                    color = glyphColor.copy(alpha = (1f - progress).coerceIn(0f, 1f)),
                    knockout = bodyColor,
                )
            }
            withTransform({
                scale(scaleX = inScale, scaleY = inScale, pivot = pivot)
            }) {
                drawDroidGlyph(
                    glyph = toGlyph,
                    cx = cx,
                    chinY = chinY,
                    headR = headR,
                    color = glyphColor.copy(alpha = progress.coerceIn(0f, 1f)),
                    knockout = bodyColor,
                )
            }
        } else {
            drawDroidGlyph(
                glyph = toGlyph,
                cx = cx,
                chinY = chinY,
                headR = headR,
                color = glyphColor,
                knockout = bodyColor,
            )
        }
    }
}

/** Pose helper retained for unit tests / mood → glyph mapping checks. */
internal data class DroidFacePose(
    val glyph: DroidFaceGlyph,
)

internal fun AssistantMood.toDroidFacePose(): DroidFacePose =
    DroidFacePose(glyph = toDroidFaceGlyph())

private fun DrawScope.drawDroidAntennae(
    cx: Float,
    chinY: Float,
    headR: Float,
    color: Color,
) {
    // Antennae — thin stems + round tips, ~28° from vertical (not morphed).
    val antLen = headR * 0.26f
    val antW = headR * 0.06f
    val tipR = headR * 0.055f
    val antBaseY = chinY - headR * 0.82f
    val antSpread = headR * 0.30f
    val antAngle = 28f

    fun antenna(sign: Float) {
        val pivot = Offset(cx + sign * antSpread, antBaseY)
        rotate(degrees = sign * antAngle, pivot = pivot) {
            val tip = Offset(pivot.x, pivot.y - antLen)
            drawLine(
                color = color,
                start = pivot,
                end = tip,
                strokeWidth = antW,
                cap = StrokeCap.Round,
            )
            drawCircle(color = color, radius = tipR, center = tip)
        }
    }
    antenna(-1f)
    antenna(1f)
}

private fun DrawScope.drawDroidGlyph(
    glyph: DroidFaceGlyph,
    cx: Float,
    chinY: Float,
    headR: Float,
    color: Color,
    knockout: Color,
) {
    // Eyes high in the dome; mouth well above chin so strokes never clip.
    val faceCy = chinY - headR * 0.48f
    val eyeGap = headR * 0.32f
    val eyeR = headR * 0.095f
    val left = Offset(cx - eyeGap, faceCy)
    val right = Offset(cx + eyeGap, faceCy)
    val mouthY = chinY - headR * 0.20f
    val stroke = headR * 0.065f
    val contentCy = chinY - headR * 0.38f

    when (glyph) {
        DroidFaceGlyph.Happy -> {
            drawCircle(color, eyeR, left)
            drawCircle(color, eyeR, right)
            drawSmile(cx, mouthY, headR * 0.20f, headR * 0.10f, stroke, color)
        }
        DroidFaceGlyph.Wink -> {
            drawCircle(color, eyeR, left)
            drawHappyArc(right, eyeR * 1.15f, stroke * 0.95f, color)
            drawSmile(cx, mouthY, headR * 0.20f, headR * 0.10f, stroke, color)
        }
        DroidFaceGlyph.SquintSmile -> {
            drawHappyArc(left, eyeR * 1.2f, stroke * 0.95f, color)
            drawHappyArc(right, eyeR * 1.2f, stroke * 0.95f, color)
            drawSmile(cx, mouthY, headR * 0.20f, headR * 0.10f, stroke, color)
        }
        DroidFaceGlyph.Surprised -> {
            drawCircle(color, eyeR, left)
            drawCircle(color, eyeR, right)
            drawCircle(color, headR * 0.08f, Offset(cx, mouthY))
        }
        DroidFaceGlyph.Laughing -> {
            drawHappyArc(left, eyeR * 1.15f, stroke * 0.95f, color)
            drawHappyArc(right, eyeR * 1.15f, stroke * 0.95f, color)
            // Open laugh mouth — fully above chin
            val mw = headR * 0.48f
            val mh = headR * 0.22f
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(cx - mw * 0.5f, mouthY - mh * 0.15f),
                size = Size(mw, mh),
            )
        }
        DroidFaceGlyph.Cool -> {
            drawSunglasses(cx, faceCy, headR, color)
        }
        DroidFaceGlyph.StarEyes -> {
            drawStar(left, eyeR * 1.45f, color)
            drawStar(right, eyeR * 1.45f, color)
        }
        DroidFaceGlyph.HeartEyes -> {
            drawHeart(left, eyeR * 1.55f, color)
            drawHeart(right, eyeR * 1.55f, color)
        }
        DroidFaceGlyph.Dizzy -> {
            drawX(left, eyeR * 1.05f, stroke * 0.9f, color)
            drawX(right, eyeR * 1.05f, stroke * 0.9f, color)
            drawLine(
                color,
                Offset(cx - headR * 0.16f, mouthY),
                Offset(cx + headR * 0.16f, mouthY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        DroidFaceGlyph.Neutral -> {
            drawCircle(color, eyeR, left)
            drawCircle(color, eyeR, right)
            drawLine(
                color,
                Offset(cx - headR * 0.16f, mouthY),
                Offset(cx + headR * 0.16f, mouthY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        DroidFaceGlyph.Sleeping -> {
            drawSleepEye(left, eyeR * 1.25f, stroke, color)
            drawSleepEye(right, eyeR * 1.25f, stroke, color)
        }
        DroidFaceGlyph.Sad -> {
            drawCircle(color, eyeR, left)
            drawCircle(color, eyeR, right)
            // Frown curves upward toward chin — keep tip above chin pad
            drawSmile(cx, mouthY - headR * 0.02f, headR * 0.20f, -headR * 0.10f, stroke, color)
        }
        DroidFaceGlyph.Success -> drawCheck(cx, contentCy, headR * 0.42f, stroke * 1.35f, color)
        DroidFaceGlyph.Error -> drawX(Offset(cx, contentCy), headR * 0.32f, stroke * 1.35f, color)
        DroidFaceGlyph.Alert -> drawExclamation(cx, contentCy, headR, color)
        DroidFaceGlyph.Help -> drawQuestion(cx, contentCy, headR, color)
        DroidFaceGlyph.Ring -> {
            drawCircle(
                color = color,
                radius = headR * 0.28f,
                center = Offset(cx, contentCy),
                style = Stroke(width = stroke * 1.2f),
            )
        }
        DroidFaceGlyph.Search -> drawSearch(cx, contentCy, headR, color)
        DroidFaceGlyph.ArrowUp -> drawArrow(cx, contentCy, headR * 0.38f, 0f, color)
        DroidFaceGlyph.ArrowRight -> drawArrow(cx, contentCy, headR * 0.38f, 90f, color)
        DroidFaceGlyph.ArrowDown -> drawArrow(cx, contentCy, headR * 0.38f, 180f, color)
        DroidFaceGlyph.ArrowLeft -> drawArrow(cx, contentCy, headR * 0.38f, 270f, color)
        DroidFaceGlyph.ThumbsUp -> drawThumbs(cx, contentCy, headR, upright = true, color = color)
        DroidFaceGlyph.ThumbsDown -> drawThumbs(cx, contentCy, headR, upright = false, color = color)
        DroidFaceGlyph.Play -> {
            val s = headR * 0.38f
            val path = Path().apply {
                moveTo(cx - s * 0.35f, contentCy - s * 0.55f)
                lineTo(cx + s * 0.55f, contentCy)
                lineTo(cx - s * 0.35f, contentCy + s * 0.55f)
                close()
            }
            drawPath(path, color)
        }
        DroidFaceGlyph.Chat -> drawChat(cx, contentCy, headR, color)
        DroidFaceGlyph.User -> drawUser(cx, contentCy, headR, color)
        DroidFaceGlyph.Warning -> drawWarning(cx, contentCy, headR, color)
        DroidFaceGlyph.Lock -> drawLock(cx, contentCy, headR, color)
        DroidFaceGlyph.Shield -> drawShield(cx, contentCy, headR, color, knockout)
        DroidFaceGlyph.Waveform -> drawWaveform(cx, contentCy, headR, color)
        DroidFaceGlyph.Settings -> drawGear(cx, contentCy, headR * 0.34f, color, knockout)
        DroidFaceGlyph.Signal -> drawSignal(cx, contentCy, headR, color)
        DroidFaceGlyph.Dollar -> drawDollar(cx, contentCy, headR, color)
        DroidFaceGlyph.Ellipsis -> {
            val r = headR * 0.08f
            val g = headR * 0.22f
            drawCircle(color, r, Offset(cx - g, contentCy))
            drawCircle(color, r, Offset(cx, contentCy))
            drawCircle(color, r, Offset(cx + g, contentCy))
        }
        DroidFaceGlyph.Hi -> drawHi(cx, contentCy, headR, color)
    }
}

// ── Face primitives ──────────────────────────────────────────────────────────

private fun DrawScope.drawHappyArc(c: Offset, r: Float, stroke: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x - r, c.y + r * 0.2f)
        quadraticTo(c.x, c.y - r * 0.95f, c.x + r, c.y + r * 0.2f)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

private fun DrawScope.drawSleepEye(c: Offset, r: Float, stroke: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x - r, c.y - r * 0.15f)
        quadraticTo(c.x, c.y + r * 0.85f, c.x + r, c.y - r * 0.15f)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

private fun DrawScope.drawSmile(
    cx: Float,
    cy: Float,
    halfW: Float,
    curve: Float,
    stroke: Float,
    color: Color,
) {
    val path = Path().apply {
        moveTo(cx - halfW, cy)
        quadraticTo(cx, cy + curve, cx + halfW, cy)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

private fun DrawScope.drawX(c: Offset, r: Float, stroke: Float, color: Color) {
    drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), stroke, StrokeCap.Round)
    drawLine(color, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), stroke, StrokeCap.Round)
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path()
    val points = 5
    for (i in 0 until points * 2) {
        val rad = if (i % 2 == 0) r else r * 0.4f
        val a = -PI.toFloat() / 2f + i * PI.toFloat() / points
        val x = c.x + cos(a) * rad
        val y = c.y + sin(a) * rad
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawHeart(c: Offset, s: Float, color: Color) {
    val path = Path().apply {
        val top = c.y - s * 0.15f
        moveTo(c.x, c.y + s * 0.55f)
        cubicTo(
            c.x - s * 1.1f, c.y + s * 0.1f,
            c.x - s * 0.95f, top - s * 0.55f,
            c.x, top,
        )
        cubicTo(
            c.x + s * 0.95f, top - s * 0.55f,
            c.x + s * 1.1f, c.y + s * 0.1f,
            c.x, c.y + s * 0.55f,
        )
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawSunglasses(cx: Float, cy: Float, headR: Float, color: Color) {
    val lensW = headR * 0.34f
    val lensH = headR * 0.22f
    val gap = headR * 0.06f
    val leftX = cx - gap * 0.5f - lensW
    val rightX = cx + gap * 0.5f
    val y = cy - lensH * 0.35f
    val r = CornerRadius(lensH * 0.35f, lensH * 0.35f)
    drawRoundRect(color, Offset(leftX, y), Size(lensW, lensH), r)
    drawRoundRect(color, Offset(rightX, y), Size(lensW, lensH), r)
    drawRoundRect(
        color,
        Offset(cx - gap * 0.5f, cy - lensH * 0.05f),
        Size(gap, lensH * 0.28f),
        CornerRadius(lensH * 0.1f),
    )
    // Temples
    drawLine(
        color,
        Offset(leftX, cy),
        Offset(leftX - headR * 0.12f, cy - headR * 0.04f),
        headR * 0.06f,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(rightX + lensW, cy),
        Offset(rightX + lensW + headR * 0.12f, cy - headR * 0.04f),
        headR * 0.06f,
        StrokeCap.Round,
    )
}

// ── Status / utility glyphs ──────────────────────────────────────────────────

private fun DrawScope.drawCheck(cx: Float, cy: Float, s: Float, stroke: Float, color: Color) {
    val path = Path().apply {
        moveTo(cx - s * 0.45f, cy)
        lineTo(cx - s * 0.1f, cy + s * 0.35f)
        lineTo(cx + s * 0.5f, cy - s * 0.4f)
    }
    drawPath(
        path,
        color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawExclamation(cx: Float, cy: Float, headR: Float, color: Color) {
    val w = headR * 0.12f
    val h = headR * 0.38f
    drawRoundRect(
        color,
        Offset(cx - w * 0.5f, cy - h * 0.55f),
        Size(w, h * 0.72f),
        CornerRadius(w * 0.5f),
    )
    drawCircle(color, w * 0.55f, Offset(cx, cy + h * 0.42f))
}

private fun DrawScope.drawQuestion(cx: Float, cy: Float, headR: Float, color: Color) {
    val stroke = headR * 0.1f
    val r = headR * 0.18f
    drawArc(
        color = color,
        startAngle = 200f,
        sweepAngle = 220f,
        useCenter = false,
        topLeft = Offset(cx - r, cy - headR * 0.32f),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(
        color,
        Offset(cx, cy - headR * 0.02f),
        Offset(cx, cy + headR * 0.08f),
        stroke,
        StrokeCap.Round,
    )
    drawCircle(color, stroke * 0.55f, Offset(cx, cy + headR * 0.22f))
}

private fun DrawScope.drawSearch(cx: Float, cy: Float, headR: Float, color: Color) {
    val r = headR * 0.22f
    val stroke = headR * 0.09f
    val c = Offset(cx - headR * 0.06f, cy - headR * 0.04f)
    drawCircle(color, r, c, style = Stroke(width = stroke))
    drawLine(
        color,
        Offset(c.x + r * 0.72f, c.y + r * 0.72f),
        Offset(c.x + r * 1.45f, c.y + r * 1.45f),
        stroke,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawArrow(cx: Float, cy: Float, s: Float, rotation: Float, color: Color) {
    rotate(rotation, Offset(cx, cy)) {
        val stroke = s * 0.22f
        // Shaft
        drawLine(
            color,
            Offset(cx, cy + s * 0.45f),
            Offset(cx, cy - s * 0.35f),
            stroke,
            StrokeCap.Round,
        )
        // Head
        val path = Path().apply {
            moveTo(cx - s * 0.4f, cy - s * 0.05f)
            lineTo(cx, cy - s * 0.5f)
            lineTo(cx + s * 0.4f, cy - s * 0.05f)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.drawThumbs(
    cx: Float,
    cy: Float,
    headR: Float,
    upright: Boolean,
    color: Color,
) {
    val s = headR * 0.42f
    rotate(if (upright) 0f else 180f, Offset(cx, cy)) {
        // Fist block
        drawRoundRect(
            color,
            Offset(cx - s * 0.45f, cy - s * 0.05f),
            Size(s * 0.9f, s * 0.55f),
            CornerRadius(s * 0.12f),
        )
        // Thumb
        drawRoundRect(
            color,
            Offset(cx - s * 0.15f, cy - s * 0.55f),
            Size(s * 0.32f, s * 0.55f),
            CornerRadius(s * 0.16f),
        )
    }
}

private fun DrawScope.drawChat(
    cx: Float,
    cy: Float,
    headR: Float,
    color: Color,
) {
    val w = headR * 0.72f
    val h = headR * 0.48f
    val left = cx - w * 0.5f
    val top = cy - h * 0.55f
    val bubble = Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = left + w,
                bottom = top + h * 0.85f,
                cornerRadius = CornerRadius(h * 0.22f),
            ),
        )
        moveTo(left + w * 0.18f, top + h * 0.75f)
        lineTo(left + w * 0.08f, top + h * 1.15f)
        lineTo(left + w * 0.38f, top + h * 0.75f)
        close()
    }
    val dots = Path().apply {
        val dy = top + h * 0.42f
        val r = h * 0.09f
        val g = w * 0.18f
        addOval(Rect(cx - g - r, dy - r, cx - g + r, dy + r))
        addOval(Rect(cx - r, dy - r, cx + r, dy + r))
        addOval(Rect(cx + g - r, dy - r, cx + g + r, dy + r))
    }
    drawPath(Path.combine(PathOperation.Difference, bubble, dots), color)
}

private fun DrawScope.drawUser(cx: Float, cy: Float, headR: Float, color: Color) {
    val head = headR * 0.16f
    drawCircle(color, head, Offset(cx, cy - headR * 0.12f))
    val shoulders = Path().apply {
        addArc(
            oval = Rect(
                cx - headR * 0.32f,
                cy + headR * 0.02f,
                cx + headR * 0.32f,
                cy + headR * 0.55f,
            ),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
        )
        close()
    }
    drawPath(shoulders, color)
}

private fun DrawScope.drawWarning(cx: Float, cy: Float, headR: Float, color: Color) {
    val s = headR * 0.48f
    val tri = Path().apply {
        moveTo(cx, cy - s * 0.55f)
        lineTo(cx + s * 0.55f, cy + s * 0.45f)
        lineTo(cx - s * 0.55f, cy + s * 0.45f)
        close()
    }
    drawPath(tri, color, style = Stroke(width = headR * 0.09f, join = StrokeJoin.Round))
    // Bang inside — use fill bang; for white-on-green stroke triangle, fill bang white
    val w = headR * 0.07f
    drawRoundRect(
        color,
        Offset(cx - w * 0.5f, cy - s * 0.2f),
        Size(w, s * 0.35f),
        CornerRadius(w),
    )
    drawCircle(color, w * 0.55f, Offset(cx, cy + s * 0.28f))
}

private fun DrawScope.drawLock(cx: Float, cy: Float, headR: Float, color: Color) {
    val bodyW = headR * 0.42f
    val bodyH = headR * 0.36f
    val bodyTop = cy - headR * 0.02f
    drawRoundRect(
        color,
        Offset(cx - bodyW * 0.5f, bodyTop),
        Size(bodyW, bodyH),
        CornerRadius(headR * 0.06f),
    )
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx - bodyW * 0.32f, cy - headR * 0.38f),
        size = Size(bodyW * 0.64f, bodyW * 0.7f),
        style = Stroke(width = headR * 0.08f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawShield(
    cx: Float,
    cy: Float,
    headR: Float,
    color: Color,
    knockout: Color,
) {
    val w = headR * 0.5f
    val h = headR * 0.58f
    val path = Path().apply {
        moveTo(cx, cy - h * 0.5f)
        lineTo(cx + w * 0.5f, cy - h * 0.28f)
        lineTo(cx + w * 0.42f, cy + h * 0.15f)
        quadraticTo(cx, cy + h * 0.55f, cx - w * 0.42f, cy + h * 0.15f)
        lineTo(cx - w * 0.5f, cy - h * 0.28f)
        close()
    }
    drawPath(path, color)
    val check = Path().apply {
        moveTo(cx - w * 0.18f, cy)
        lineTo(cx - w * 0.02f, cy + h * 0.14f)
        lineTo(cx + w * 0.22f, cy - h * 0.12f)
    }
    drawPath(
        check,
        knockout,
        style = Stroke(width = headR * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawWaveform(cx: Float, cy: Float, headR: Float, color: Color) {
    val heights = floatArrayOf(0.35f, 0.7f, 1f, 0.55f, 0.85f, 0.4f)
    val barW = headR * 0.08f
    val gap = headR * 0.07f
    val total = heights.size * barW + (heights.size - 1) * gap
    var x = cx - total * 0.5f
    heights.forEach { h ->
        val bh = headR * 0.5f * h
        drawRoundRect(
            color,
            Offset(x, cy - bh * 0.5f),
            Size(barW, bh),
            CornerRadius(barW * 0.5f),
        )
        x += barW + gap
    }
}

private fun DrawScope.drawGear(
    cx: Float,
    cy: Float,
    r: Float,
    color: Color,
    knockout: Color,
) {
    val teeth = 8
    val path = Path()
    for (i in 0 until teeth) {
        val a0 = i * 2f * PI.toFloat() / teeth
        val a1 = a0 + PI.toFloat() / teeth * 0.55f
        val a2 = a0 + PI.toFloat() / teeth
        val a3 = a0 + PI.toFloat() / teeth * 1.45f
        val outer = r
        val inner = r * 0.68f
        fun pt(a: Float, rad: Float) = Offset(cx + cos(a) * rad, cy + sin(a) * rad)
        val p0 = pt(a0, inner)
        if (i == 0) path.moveTo(p0.x, p0.y) else path.lineTo(p0.x, p0.y)
        path.lineTo(pt(a1, outer).x, pt(a1, outer).y)
        path.lineTo(pt(a2, outer).x, pt(a2, outer).y)
        path.lineTo(pt(a3, inner).x, pt(a3, inner).y)
    }
    path.close()
    drawPath(path, color)
    drawCircle(knockout, r * 0.32f, Offset(cx, cy))
}

private fun DrawScope.drawSignal(cx: Float, cy: Float, headR: Float, color: Color) {
    val heights = floatArrayOf(0.35f, 0.65f, 1f)
    val barW = headR * 0.14f
    val gap = headR * 0.1f
    val total = heights.size * barW + (heights.size - 1) * gap
    var x = cx - total * 0.5f
    val base = cy + headR * 0.22f
    heights.forEach { h ->
        val bh = headR * 0.5f * h
        drawRoundRect(
            color,
            Offset(x, base - bh),
            Size(barW, bh),
            CornerRadius(barW * 0.25f),
        )
        x += barW + gap
    }
}

private fun DrawScope.drawDollar(cx: Float, cy: Float, headR: Float, color: Color) {
    val stroke = headR * 0.1f
    val r = headR * 0.2f
    // S curve as two arcs
    drawArc(
        color = color,
        startAngle = 40f,
        sweepAngle = 200f,
        useCenter = false,
        topLeft = Offset(cx - r, cy - headR * 0.28f),
        size = Size(r * 1.7f, r * 1.3f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawArc(
        color = color,
        startAngle = 220f,
        sweepAngle = 200f,
        useCenter = false,
        topLeft = Offset(cx - r * 0.7f, cy - headR * 0.02f),
        size = Size(r * 1.7f, r * 1.3f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(
        color,
        Offset(cx, cy - headR * 0.38f),
        Offset(cx, cy + headR * 0.38f),
        stroke * 0.85f,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawHi(cx: Float, cy: Float, headR: Float, color: Color) {
    // Block-letter HI! drawn as geometry
    val h = headR * 0.42f
    val t = headR * 0.09f
    val left = cx - headR * 0.38f
    // H
    drawRoundRect(color, Offset(left, cy - h * 0.5f), Size(t, h), CornerRadius(t * 0.3f))
    drawRoundRect(color, Offset(left + t * 2.1f, cy - h * 0.5f), Size(t, h), CornerRadius(t * 0.3f))
    drawRoundRect(
        color,
        Offset(left, cy - t * 0.5f),
        Size(t * 3.1f, t),
        CornerRadius(t * 0.3f),
    )
    // I
    val iX = left + t * 4.2f
    drawRoundRect(color, Offset(iX, cy - h * 0.5f), Size(t, h), CornerRadius(t * 0.3f))
    // !
    val bangX = iX + t * 2.2f
    drawRoundRect(
        color,
        Offset(bangX, cy - h * 0.5f),
        Size(t, h * 0.62f),
        CornerRadius(t * 0.3f),
    )
    drawCircle(color, t * 0.55f, Offset(bangX + t * 0.5f, cy + h * 0.42f))
}

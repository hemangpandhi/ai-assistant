package com.test.design.presentation.assistant

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val PropWhite = Color(0xFFF5F7FF)
private val PropSoft = Color(0xFFE8ECFF)
private val PropAccent = Color(0xFFB39DDB)

/**
 * Floating mood props — thought cloud, scan glass, and friends.
 * [visibility] 0..1 fades/scales the active prop in seamlessly.
 */
internal fun DrawScope.drawMoodProp(
    mood: AssistantMood,
    center: Offset,
    shell: Float,
    visibility: Float,
    life: Float,
) {
    if (visibility < 0.02f) return
    val bob = sin(life).toFloat() * shell * 0.018f
    when (mood) {
        AssistantMood.Thinking -> drawThoughtCloud(
            anchor = Offset(center.x + shell * 0.42f, center.y - shell * 0.48f + bob),
            size = shell * 0.38f * (0.85f + 0.15f * visibility),
            alpha = visibility,
            life = life,
        )
        AssistantMood.Searching -> drawScanGlass(
            anchor = Offset(center.x + shell * 0.48f, center.y - shell * 0.28f + bob),
            size = shell * 0.34f * (0.85f + 0.15f * visibility),
            alpha = visibility,
            life = life,
        )
        AssistantMood.Reading -> drawTinyBook(
            anchor = Offset(center.x + shell * 0.46f, center.y - shell * 0.36f + bob),
            size = shell * 0.3f * (0.85f + 0.15f * visibility),
            alpha = visibility,
            life = life,
        )
        AssistantMood.Happy -> drawSparkles(
            anchor = center,
            shell = shell,
            alpha = visibility,
            life = life,
        )
        AssistantMood.Listening -> drawListenRings(
            anchor = Offset(center.x + shell * 0.52f, center.y),
            size = shell * 0.28f,
            alpha = visibility,
            life = life,
        )
        AssistantMood.Sad -> drawTeardrop(
            anchor = Offset(center.x + shell * 0.12f, center.y + shell * 0.18f + bob * 0.5f),
            size = shell * 0.1f * visibility,
            alpha = visibility,
            life = life,
        )
        AssistantMood.Speaking -> drawSpeechDots(
            anchor = Offset(center.x + shell * 0.48f, center.y - shell * 0.4f + bob),
            size = shell * 0.22f,
            alpha = visibility,
            life = life,
        )
        AssistantMood.Excited -> drawSparkles(
            anchor = center,
            shell = shell,
            alpha = visibility,
            life = life,
        )
        AssistantMood.Bored,
        AssistantMood.Drowsy,
        AssistantMood.Tired,
        AssistantMood.Idle -> Unit
    }
}

private fun DrawScope.drawThoughtCloud(
    anchor: Offset,
    size: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    val fill = PropSoft.copy(alpha = 0.92f * a)
    val stroke = PropWhite.copy(alpha = 0.95f * a)

    // Fluffy cloud — overlapping circles
    val c1 = Offset(anchor.x, anchor.y)
    val c2 = Offset(anchor.x - size * 0.28f, anchor.y + size * 0.06f)
    val c3 = Offset(anchor.x + size * 0.3f, anchor.y + size * 0.04f)
    val c4 = Offset(anchor.x + size * 0.05f, anchor.y - size * 0.22f)
    drawCircle(fill, size * 0.32f, c1)
    drawCircle(fill, size * 0.26f, c2)
    drawCircle(fill, size * 0.28f, c3)
    drawCircle(fill, size * 0.24f, c4)
    drawCircle(stroke, size * 0.32f, c1, style = Stroke(1.5f))
    drawCircle(stroke, size * 0.26f, c2, style = Stroke(1.5f))
    drawCircle(stroke, size * 0.28f, c3, style = Stroke(1.5f))
    drawCircle(stroke, size * 0.24f, c4, style = Stroke(1.5f))

    // Trail bubbles toward the face
    drawCircle(fill, size * 0.08f, Offset(anchor.x - size * 0.42f, anchor.y + size * 0.32f))
    drawCircle(fill, size * 0.05f, Offset(anchor.x - size * 0.55f, anchor.y + size * 0.48f))

    // Bouncing think dots
    val dotY = anchor.y + size * 0.02f
    for (i in 0..2) {
        val bounce = sin(life * 2f + i * 0.9f).toFloat() * size * 0.06f
        drawCircle(
            color = PropAccent.copy(alpha = 0.9f * a),
            radius = size * 0.055f,
            center = Offset(anchor.x - size * 0.16f + i * size * 0.16f, dotY + bounce),
        )
    }
}

private fun DrawScope.drawScanGlass(
    anchor: Offset,
    size: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    val stroke = PropWhite.copy(alpha = 0.95f * a)
    val glass = PropSoft.copy(alpha = 0.35f * a)
    val rim = PropAccent.copy(alpha = 0.85f * a)
    val angle = 18f + 8f * sin(life).toFloat()

    rotate(angle, pivot = anchor) {
        val r = size * 0.32f
        drawCircle(glass, r, anchor)
        drawCircle(rim, r, anchor, style = Stroke(width = size * 0.07f))
        drawCircle(stroke, r, anchor, style = Stroke(width = size * 0.035f))
        // Handle
        val handleStart = Offset(anchor.x + r * 0.72f, anchor.y + r * 0.72f)
        val handleEnd = Offset(anchor.x + r * 1.45f, anchor.y + r * 1.45f)
        drawLine(
            color = stroke,
            start = handleStart,
            end = handleEnd,
            strokeWidth = size * 0.08f,
            cap = StrokeCap.Round,
        )
        // Soft scan sweep inside lens
        val sweep = ((sin(life * 1.4f) + 1f) * 0.5f)
        drawLine(
            color = Color(0xFF40C4FF).copy(alpha = 0.45f * a),
            start = Offset(anchor.x - r * 0.55f, anchor.y - r * 0.2f + sweep * r * 0.5f),
            end = Offset(anchor.x + r * 0.55f, anchor.y - r * 0.2f + sweep * r * 0.5f),
            strokeWidth = size * 0.03f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawTinyBook(
    anchor: Offset,
    size: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    val w = size * 0.7f
    val h = size * 0.5f
    val flutter = 4f * sin(life).toFloat()
    rotate(flutter, pivot = anchor) {
        drawRoundRect(
            color = PropSoft.copy(alpha = 0.9f * a),
            topLeft = Offset(anchor.x - w * 0.5f, anchor.y - h * 0.5f),
            size = Size(w, h),
            cornerRadius = CornerRadius(size * 0.06f),
        )
        drawLine(
            color = PropAccent.copy(alpha = 0.8f * a),
            start = Offset(anchor.x, anchor.y - h * 0.45f),
            end = Offset(anchor.x, anchor.y + h * 0.45f),
            strokeWidth = size * 0.04f,
        )
        // Text lines
        for (i in 0..2) {
            val y = anchor.y - h * 0.2f + i * h * 0.18f
            drawLine(
                color = PropWhite.copy(alpha = 0.55f * a),
                start = Offset(anchor.x - w * 0.32f, y),
                end = Offset(anchor.x - w * 0.08f, y),
                strokeWidth = 1.5f,
            )
            drawLine(
                color = PropWhite.copy(alpha = 0.55f * a),
                start = Offset(anchor.x + w * 0.08f, y),
                end = Offset(anchor.x + w * 0.32f, y),
                strokeWidth = 1.5f,
            )
        }
    }
}

private fun DrawScope.drawSparkles(
    anchor: Offset,
    shell: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    val spots = listOf(
        Offset(anchor.x - shell * 0.48f, anchor.y - shell * 0.35f),
        Offset(anchor.x + shell * 0.5f, anchor.y - shell * 0.42f),
        Offset(anchor.x + shell * 0.42f, anchor.y + shell * 0.2f),
        Offset(anchor.x - shell * 0.4f, anchor.y + shell * 0.28f),
    )
    spots.forEachIndexed { i, p ->
        val twinkle = 0.55f + 0.45f * ((sin(life * 2f + i) + 1f) * 0.5f).toFloat()
        val s = shell * 0.045f * twinkle
        drawStar(p, s, Color(0xFFFFD54F).copy(alpha = 0.85f * a * twinkle))
    }
}

private fun DrawScope.drawStar(center: Offset, size: Float, color: Color) {
    drawLine(
        color,
        Offset(center.x, center.y - size),
        Offset(center.x, center.y + size),
        strokeWidth = size * 0.35f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(center.x - size, center.y),
        Offset(center.x + size, center.y),
        strokeWidth = size * 0.35f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawListenRings(
    anchor: Offset,
    size: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    for (i in 0..2) {
        val t = ((life / (2f * PI.toFloat()) + i * 0.28f) % 1f)
        val r = size * (0.35f + t * 0.9f)
        drawCircle(
            color = Color(0xFF40C4FF).copy(alpha = (1f - t) * 0.45f * a),
            radius = r,
            center = anchor,
            style = Stroke(width = size * 0.06f * (1f - t * 0.5f)),
        )
    }
}

private fun DrawScope.drawTeardrop(
    anchor: Offset,
    size: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    val drip = ((sin(life) + 1f) * 0.5f).toFloat() * size * 0.35f
    val path = Path().apply {
        moveTo(anchor.x, anchor.y - size + drip)
        quadraticTo(anchor.x + size * 0.7f, anchor.y + size * 0.2f + drip, anchor.x, anchor.y + size + drip)
        quadraticTo(anchor.x - size * 0.7f, anchor.y + size * 0.2f + drip, anchor.x, anchor.y - size + drip)
        close()
    }
    drawPath(path, Color(0xFF81D4FA).copy(alpha = 0.75f * a))
}

private fun DrawScope.drawSpeechDots(
    anchor: Offset,
    size: Float,
    alpha: Float,
    life: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    for (i in 0..2) {
        val pop = 0.65f + 0.35f * ((sin(life * 2.2f + i * 1.1f) + 1f) * 0.5f).toFloat()
        drawCircle(
            color = PropWhite.copy(alpha = 0.85f * a * pop),
            radius = size * 0.1f * pop,
            center = Offset(anchor.x + i * size * 0.28f, anchor.y - i * size * 0.08f),
        )
    }
}

/** Soft cheek blush for a cuter persona. */
internal fun DrawScope.drawCheekBlush(
    left: Offset,
    right: Offset,
    radius: Float,
    amount: Float,
) {
    if (amount < 0.02f) return
    val color = Color(0xFFFF8A9B).copy(alpha = 0.42f * amount)
    drawCircle(color, radius, left)
    drawCircle(color, radius, right)
}

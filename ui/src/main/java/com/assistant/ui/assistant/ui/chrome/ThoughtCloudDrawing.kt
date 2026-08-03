package com.assistant.ui.assistant.ui.chrome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

private val ThoughtCloudFill = Color(0xFFE8ECFF)
private val ThoughtCloudStroke = Color(0xFFF5F7FF)
private val ThoughtCloudDot = Color(0xFFB39DDB)

/**
 * Shared fluffy thought-cloud used by the thinking overlay and mood props.
 */
internal fun DrawScope.drawThoughtCloud(
    anchor: Offset,
    cloudSize: Float,
    life: Float,
    alpha: Float = 1f,
    fill: Color = ThoughtCloudFill,
    stroke: Color = ThoughtCloudStroke,
    dot: Color = ThoughtCloudDot,
) {
    val a = alpha.coerceIn(0f, 1f)
    val fillPaint = fill.copy(alpha = 0.92f * a)
    val strokePaint = stroke.copy(alpha = 0.95f * a)
    val s = cloudSize

    val c1 = Offset(anchor.x, anchor.y)
    val c2 = Offset(anchor.x - s * 0.28f, anchor.y + s * 0.06f)
    val c3 = Offset(anchor.x + s * 0.3f, anchor.y + s * 0.04f)
    val c4 = Offset(anchor.x + s * 0.05f, anchor.y - s * 0.22f)
    drawCircle(fillPaint, s * 0.32f, c1)
    drawCircle(fillPaint, s * 0.26f, c2)
    drawCircle(fillPaint, s * 0.28f, c3)
    drawCircle(fillPaint, s * 0.24f, c4)
    drawCircle(strokePaint, s * 0.32f, c1, style = Stroke(1.5f))
    drawCircle(strokePaint, s * 0.26f, c2, style = Stroke(1.5f))
    drawCircle(strokePaint, s * 0.28f, c3, style = Stroke(1.5f))
    drawCircle(strokePaint, s * 0.24f, c4, style = Stroke(1.5f))

    drawCircle(fillPaint, s * 0.08f, Offset(anchor.x - s * 0.42f, anchor.y + s * 0.32f))
    drawCircle(fillPaint, s * 0.05f, Offset(anchor.x - s * 0.55f, anchor.y + s * 0.48f))

    val dotY = anchor.y + s * 0.02f
    for (i in 0..2) {
        val bounce = sin(life * 2f + i * 0.9f).toFloat() * s * 0.06f
        drawCircle(
            color = dot.copy(alpha = 0.9f * a),
            radius = s * 0.055f,
            center = Offset(anchor.x - s * 0.16f + i * s * 0.16f, dotY + bounce),
        )
    }
}

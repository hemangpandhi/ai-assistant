package com.assistant.ui.assistant.face

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

/** In-shell white glyph props from the Nomi-Mate sheet (never drawn outside the face). */
private val DecorWhite = Color(0xFFF5F7FA)
private val BlushPink = Color(0xFFFF9BB0)

/** Scratch paths reused on the Compose draw thread (UI thread only). */
private val HeartPathScratch = Path()
private val CrownPathScratch = Path()

internal enum class NomiHandPose {
    None,
    Cheeks,
    Clap,
    Raised,
    Hips,
    Chin,
    Gratitude,
    Victory,
}

internal enum class NomiProp {
    None,
    Crown,
    Glasses,
}

/**
 * Additive sheet decor for an affective mood. Everything is positioned inside the
 * face shell; the caller must clip to the shell path.
 */
internal data class NomiMateDecor(
    val hands: NomiHandPose = NomiHandPose.None,
    val hearts: Int = 0,
    val sparkles: Int = 0,
    val zzz: Boolean = false,
    val prop: NomiProp = NomiProp.None,
    val cheekBlush: Float = 0f,
)

/** Pipeline moods stay bare; sheet decor only for affective / LLM moods. */
internal fun AssistantMood.toNomiMateDecor(): NomiMateDecor = when (this) {
    AssistantMood.Idle,
    AssistantMood.Listening,
    AssistantMood.Speaking,
    AssistantMood.Thinking,
    AssistantMood.Reading,
    AssistantMood.Searching,
    -> NomiMateDecor()

    AssistantMood.Attraction -> NomiMateDecor(
        hands = NomiHandPose.Cheeks,
        sparkles = 2,
        cheekBlush = 0.55f,
    )
    AssistantMood.Admiration -> NomiMateDecor(
        hands = NomiHandPose.Cheeks,
        sparkles = 3,
        cheekBlush = 0.45f,
    )
    AssistantMood.Desire -> NomiMateDecor(
        hands = NomiHandPose.Cheeks,
        hearts = 1,
        sparkles = 1,
        cheekBlush = 0.65f,
    )
    AssistantMood.Interest,
    AssistantMood.Surprise,
    -> NomiMateDecor(sparkles = 1)
    AssistantMood.Astonishment -> NomiMateDecor(
        hands = NomiHandPose.Cheeks,
        sparkles = 2,
    )
    AssistantMood.Happy -> NomiMateDecor(cheekBlush = 0.35f)
    AssistantMood.Amused -> NomiMateDecor(cheekBlush = 0.7f)
    AssistantMood.Joyous -> NomiMateDecor(sparkles = 3, cheekBlush = 0.55f)
    AssistantMood.Excited -> NomiMateDecor(
        hands = NomiHandPose.Cheeks,
        sparkles = 3,
        cheekBlush = 0.6f,
    )
    AssistantMood.Jubilation -> NomiMateDecor(
        hands = NomiHandPose.Raised,
        hearts = 2,
        sparkles = 3,
        cheekBlush = 0.5f,
    )
    AssistantMood.Gratitude -> NomiMateDecor(
        hands = NomiHandPose.Gratitude,
        sparkles = 2,
        cheekBlush = 0.4f,
    )
    AssistantMood.Contentment -> NomiMateDecor(cheekBlush = 0.2f)
    AssistantMood.Proud -> NomiMateDecor(hands = NomiHandPose.Hips)
    AssistantMood.Triumph -> NomiMateDecor(
        hands = NomiHandPose.Victory,
        prop = NomiProp.Crown,
        sparkles = 1,
    )
    AssistantMood.Relaxed -> NomiMateDecor(prop = NomiProp.Glasses)
    AssistantMood.Shy -> NomiMateDecor(
        hearts = 1,
        sparkles = 1,
        cheekBlush = 0.85f,
    )
    AssistantMood.Acceptance,
    AssistantMood.Complicity,
    -> NomiMateDecor(cheekBlush = 0.15f)
    AssistantMood.Concentration,
    AssistantMood.Dreamy,
    AssistantMood.Concerned,
    -> NomiMateDecor(hands = NomiHandPose.Chin)
    AssistantMood.Drowsy,
    AssistantMood.Tired,
    -> NomiMateDecor()
    AssistantMood.Sleeping -> NomiMateDecor(zzz = true)
    AssistantMood.Doubt -> NomiMateDecor()
    AssistantMood.Impressed -> NomiMateDecor(sparkles = 3)
    AssistantMood.Sad -> NomiMateDecor()
    AssistantMood.Bored -> NomiMateDecor()
}

internal fun DrawScope.drawNomiMateDecor(
    mood: AssistantMood,
    cx: Float,
    cy: Float,
    faceR: Float,
    color: Color = DecorWhite,
    life: Float = 0f,
    skipParticles: Boolean = false,
) {
    val decor = mood.toNomiMateDecor()
    if (decor == NomiMateDecor()) return
    val bob = sin(life).toFloat() * faceR * 0.015f

    if (decor.cheekBlush > 0.04f) {
        val a = 0.28f * decor.cheekBlush
        val bx = faceR * 0.38f
        val by = cy + faceR * 0.16f
        drawCircle(BlushPink.copy(alpha = a), faceR * 0.1f, Offset(cx - bx, by))
        drawCircle(BlushPink.copy(alpha = a), faceR * 0.1f, Offset(cx + bx, by))
    }

    drawNomiHands(decor.hands, cx, cy, faceR, color, life)

    when (decor.prop) {
        NomiProp.None -> Unit
        NomiProp.Crown -> drawNomiCrown(
            Offset(cx, cy - faceR * 0.72f + bob),
            faceR * 0.22f,
            color,
        )
        NomiProp.Glasses -> drawNomiGlasses(cx, cy - faceR * 0.02f, faceR, color)
    }

    if (!skipParticles) {
        if (decor.zzz) {
            drawNomiZzz(
                Offset(cx + faceR * 0.22f, cy - faceR * 0.55f + bob),
                faceR,
                color,
                life,
            )
        }
        if (decor.hearts > 0) {
            repeat(decor.hearts) { i ->
                val ang = (-0.9f + i * 0.35f)
                val r = faceR * (0.42f + 0.06f * i)
                drawNomiHeart(
                    Offset(
                        cx + cos(ang).toFloat() * r,
                        cy - faceR * 0.35f + sin(life * 0.4f + i).toFloat() * faceR * 0.03f,
                    ),
                    faceR * (0.08f - i * 0.012f),
                    color.copy(alpha = 0.95f),
                )
            }
        }
        if (decor.sparkles > 0) {
            repeat(decor.sparkles) { i ->
                val ang = (i * (2f * PI / decor.sparkles) + life * 0.35).toFloat()
                val r = faceR * (0.48f + 0.05f * (i % 2))
                drawNomiSparkle(
                    Offset(cx + cos(ang) * r, cy + sin(ang) * r * 0.7f - faceR * 0.05f),
                    faceR * 0.055f,
                    color.copy(alpha = 0.85f),
                    life + i,
                )
            }
        }
    }
}

private fun DrawScope.drawNomiHands(
    pose: NomiHandPose,
    cx: Float,
    cy: Float,
    faceR: Float,
    color: Color,
    life: Float,
) {
    val wiggle = sin(life * 1.2f).toFloat() * faceR * 0.015f
    when (pose) {
        NomiHandPose.None -> Unit
        NomiHandPose.Cheeks -> {
            drawNomiPaw(Offset(cx - faceR * 0.55f, cy + faceR * 0.08f), faceR * 0.13f, color, -12f)
            drawNomiPaw(Offset(cx + faceR * 0.55f, cy + faceR * 0.08f), faceR * 0.13f, color, 12f)
        }
        NomiHandPose.Clap -> {
            drawNomiPaw(Offset(cx - faceR * 0.28f + wiggle, cy + faceR * 0.42f), faceR * 0.12f, color, 20f)
            drawNomiPaw(Offset(cx + faceR * 0.28f - wiggle, cy + faceR * 0.42f), faceR * 0.12f, color, -20f)
        }
        NomiHandPose.Raised -> {
            drawNomiPaw(Offset(cx - faceR * 0.32f, cy - faceR * 0.55f + wiggle), faceR * 0.12f, color, -8f)
            drawNomiPaw(Offset(cx + faceR * 0.32f, cy - faceR * 0.55f - wiggle), faceR * 0.12f, color, 8f)
        }
        NomiHandPose.Hips -> {
            drawNomiPaw(Offset(cx - faceR * 0.52f, cy + faceR * 0.42f), faceR * 0.12f, color, 30f)
            drawNomiPaw(Offset(cx + faceR * 0.52f, cy + faceR * 0.42f), faceR * 0.12f, color, -30f)
        }
        NomiHandPose.Chin -> {
            drawNomiPaw(Offset(cx + faceR * 0.1f, cy + faceR * 0.48f), faceR * 0.12f, color, 6f)
        }
        NomiHandPose.Gratitude -> {
            drawNomiPaw(Offset(cx - faceR * 0.1f, cy + faceR * 0.48f), faceR * 0.11f, color, 12f)
            drawNomiPaw(Offset(cx + faceR * 0.1f, cy + faceR * 0.48f), faceR * 0.11f, color, -12f)
        }
        NomiHandPose.Victory -> {
            drawNomiPaw(Offset(cx + faceR * 0.38f, cy - faceR * 0.35f + wiggle), faceR * 0.12f, color, -20f)
        }
    }
}

private fun DrawScope.drawNomiPaw(c: Offset, s: Float, color: Color, tilt: Float) {
    rotate(tilt, pivot = c) {
        drawRoundRect(
            color = color,
            topLeft = Offset(c.x - s * 0.55f, c.y - s * 0.45f),
            size = Size(s * 1.1f, s * 0.95f),
            cornerRadius = CornerRadius(s * 0.55f, s * 0.5f),
        )
        for (i in 0..2) {
            val fx = c.x - s * 0.28f + i * s * 0.28f
            drawCircle(color, s * 0.18f, Offset(fx, c.y - s * 0.48f))
        }
    }
}

private fun DrawScope.drawNomiHeart(c: Offset, s: Float, color: Color) {
    val path = HeartPathScratch
    path.rewind()
    val top = c.y - s * 0.15f
    path.moveTo(c.x, c.y + s * 0.55f)
    path.cubicTo(
        c.x - s * 1.1f, c.y + s * 0.1f,
        c.x - s * 0.95f, top - s * 0.55f,
        c.x, top,
    )
    path.cubicTo(
        c.x + s * 0.95f, top - s * 0.55f,
        c.x + s * 1.1f, c.y + s * 0.1f,
        c.x, c.y + s * 0.55f,
    )
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawNomiSparkle(c: Offset, s: Float, color: Color, life: Float) {
    val pulse = 0.75f + 0.25f * sin(life * 2.5f).toFloat()
    val arm = s * pulse
    drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), s * 0.35f, StrokeCap.Round)
    drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), s * 0.35f, StrokeCap.Round)
    val d = arm * 0.55f
    drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), s * 0.22f, StrokeCap.Round)
    drawLine(color, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), s * 0.22f, StrokeCap.Round)
}

private fun DrawScope.drawNomiZzz(anchor: Offset, faceR: Float, color: Color, life: Float) {
    val bob = sin(life).toFloat() * faceR * 0.02f
    for (i in 0..2) {
        val x = anchor.x + i * faceR * 0.09f
        val y = anchor.y - i * faceR * 0.1f + bob * (1f - i * 0.2f)
        val s = faceR * (0.07f + i * 0.018f)
        drawLine(color, Offset(x - s * 0.4f, y - s * 0.45f), Offset(x + s * 0.4f, y - s * 0.45f), s * 0.18f, StrokeCap.Round)
        drawLine(color, Offset(x + s * 0.4f, y - s * 0.45f), Offset(x - s * 0.4f, y + s * 0.45f), s * 0.18f, StrokeCap.Round)
        drawLine(color, Offset(x - s * 0.4f, y + s * 0.45f), Offset(x + s * 0.4f, y + s * 0.45f), s * 0.18f, StrokeCap.Round)
    }
}

private fun DrawScope.drawNomiCrown(c: Offset, s: Float, color: Color) {
    val path = CrownPathScratch
    path.rewind()
    path.moveTo(c.x - s, c.y + s * 0.35f)
    path.lineTo(c.x - s * 0.85f, c.y - s * 0.55f)
    path.lineTo(c.x - s * 0.35f, c.y + s * 0.05f)
    path.lineTo(c.x, c.y - s * 0.7f)
    path.lineTo(c.x + s * 0.35f, c.y + s * 0.05f)
    path.lineTo(c.x + s * 0.85f, c.y - s * 0.55f)
    path.lineTo(c.x + s, c.y + s * 0.35f)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawNomiGlasses(cx: Float, cy: Float, faceR: Float, color: Color) {
    val lensW = faceR * 0.26f
    val lensH = faceR * 0.16f
    val gap = faceR * 0.07f
    val leftX = cx - gap * 0.5f - lensW
    val rightX = cx + gap * 0.5f
    val y = cy - lensH * 0.15f
    val stroke = faceR * 0.04f
    val corner = CornerRadius(lensH * 0.35f)
    drawRoundRect(
        color = color,
        topLeft = Offset(leftX, y),
        size = Size(lensW, lensH),
        cornerRadius = corner,
        style = Stroke(width = stroke),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(rightX, y),
        size = Size(lensW, lensH),
        cornerRadius = corner,
        style = Stroke(width = stroke),
    )
    drawLine(
        color,
        Offset(leftX + lensW, cy + lensH * 0.15f),
        Offset(rightX, cy + lensH * 0.15f),
        stroke,
        StrokeCap.Round,
    )
}

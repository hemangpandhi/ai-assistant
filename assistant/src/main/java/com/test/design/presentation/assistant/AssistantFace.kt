package com.test.design.presentation.assistant

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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * NOMI-style face pose — white glyph eyes/mouth on a black orb.
 *
 * [eyeStyle] 0 = capsules, 1 = happy arcs, −1 = sleepy flats.
 * Mouth: [mouthCurve] −1 frown … +1 smile; [mouthOpen] 0..1.
 */
internal data class FacePose(
    val eyeOpen: Float = 1f,
    val eyeWidth: Float = 1f,
    val eyeHeight: Float = 1f,
    val eyeGap: Float = 1.45f,
    val tilt: Float = 0f,
    val borderGlow: Float = 0.85f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val blush: Float = 0f,
    val roundness: Float = 1f,
    val mouthCurve: Float = 0.2f,
    val mouthOpen: Float = 0f,
    val mouthWidth: Float = 1f,
    /** −1 sleepy · 0 capsules · 1 happy arcs */
    val eyeStyle: Float = 0f,
)

internal fun AssistantMood.toFacePose(): FacePose = when (this) {
    AssistantMood.Idle -> FacePose(
        eyeOpen = 1f,
        eyeWidth = 0.95f,
        eyeHeight = 1.05f,
        eyeGap = 1.5f,
        eyeStyle = 0.15f,
        mouthCurve = 0.25f,
        mouthOpen = 0f,
        borderGlow = 0.7f,
        blush = 0.12f,
        tilt = 0f,
    )
    AssistantMood.Listening -> FacePose(
        eyeOpen = 1.15f,
        eyeWidth = 1f,
        eyeHeight = 1.15f,
        eyeGap = 1.55f,
        eyeStyle = 0f,
        mouthCurve = 0.1f,
        mouthOpen = 0.05f,
        borderGlow = 1f,
        blush = 0.18f,
    )
    AssistantMood.Speaking -> FacePose(
        eyeOpen = 1f,
        eyeStyle = 0f,
        mouthCurve = 0.35f,
        mouthOpen = 0.55f,
        borderGlow = 0.95f,
        tilt = 3f,
    )
    AssistantMood.Thinking -> FacePose(
        eyeOpen = 0.85f,
        eyeHeight = 0.7f,
        lookX = 0.25f,
        lookY = -0.1f,
        eyeStyle = -0.35f,
        mouthCurve = 0f,
        mouthOpen = 0f,
        borderGlow = 0.8f,
        tilt = 6f,
    )
    AssistantMood.Happy -> FacePose(
        eyeOpen = 1f,
        eyeWidth = 1.1f,
        eyeGap = 1.55f,
        eyeStyle = 1f,
        mouthCurve = 0.9f,
        mouthOpen = 0.08f,
        mouthWidth = 1.1f,
        borderGlow = 1f,
        blush = 0.55f,
        tilt = -2f,
    )
    AssistantMood.Sad -> FacePose(
        eyeOpen = 0.75f,
        eyeHeight = 0.65f,
        lookY = 0.15f,
        eyeStyle = -0.7f,
        mouthCurve = -0.7f,
        mouthOpen = 0.02f,
        borderGlow = 0.55f,
        tilt = 5f,
    )
    AssistantMood.Excited -> FacePose(
        eyeOpen = 1.25f,
        eyeWidth = 1.2f,
        eyeHeight = 1.15f,
        eyeStyle = 0.2f,
        mouthCurve = 0.95f,
        mouthOpen = 0.4f,
        mouthWidth = 1.15f,
        borderGlow = 1.1f,
        blush = 0.4f,
        tilt = -3f,
    )
    AssistantMood.Bored -> FacePose(
        eyeOpen = 0.55f,
        eyeWidth = 1.15f,
        eyeHeight = 0.5f,
        lookX = 0.35f,
        eyeStyle = -0.5f,
        mouthCurve = -0.1f,
        borderGlow = 0.4f,
        tilt = 4f,
    )
    AssistantMood.Drowsy -> FacePose(
        eyeOpen = 0.35f,
        eyeWidth = 1.2f,
        eyeHeight = 0.4f,
        lookY = 0.1f,
        eyeStyle = -0.9f,
        mouthCurve = 0.05f,
        borderGlow = 0.35f,
        tilt = 3f,
    )
    AssistantMood.Tired -> FacePose(
        eyeOpen = 0.45f,
        eyeWidth = 1.1f,
        eyeHeight = 0.45f,
        lookY = 0.15f,
        eyeStyle = -0.8f,
        mouthCurve = -0.2f,
        borderGlow = 0.3f,
        tilt = 5f,
    )
    AssistantMood.Reading -> FacePose(
        eyeOpen = 0.95f,
        lookX = 0.3f,
        eyeStyle = 0f,
        mouthCurve = 0.05f,
        borderGlow = 0.75f,
    )
    AssistantMood.Searching -> FacePose(
        eyeOpen = 1.1f,
        eyeWidth = 1.05f,
        eyeStyle = 0f,
        mouthCurve = 0.2f,
        mouthOpen = 0.12f,
        borderGlow = 1f,
        tilt = 2f,
    )
}

private val PoseSpring = spring<Float>(
    dampingRatio = 0.82f,
    stiffness = Spring.StiffnessMediumLow,
)

private val OrbCore = Color(0xFF050508)
private val Glyph = Color(0xFFF5F7FA)

/**
 * NIO NOMI–style assistant — glossy glass sphere, black circular face, white glyphs.
 *
 * @param faceFillRatio Fraction of the silver shell radius filled by the matte black face.
 *   Higher values yield a thinner silver rim (e.g. `0.97f` ≈ hairline). Default `0.78f`.
 */
@Composable
fun AssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    faceColor: Color = Glyph,
    faceFillRatio: Float = 0.78f,
) {
    val target = mood.toFacePose()
    val eyeOpen = remember { Animatable(target.eyeOpen) }
    val eyeWidth = remember { Animatable(target.eyeWidth) }
    val eyeHeight = remember { Animatable(target.eyeHeight) }
    val eyeGap = remember { Animatable(target.eyeGap) }
    val tilt = remember { Animatable(target.tilt) }
    val borderGlow = remember { Animatable(target.borderGlow) }
    val lookX = remember { Animatable(target.lookX) }
    val lookY = remember { Animatable(target.lookY) }
    val blush = remember { Animatable(target.blush) }
    val mouthCurve = remember { Animatable(target.mouthCurve) }
    val mouthOpen = remember { Animatable(target.mouthOpen) }
    val mouthWidth = remember { Animatable(target.mouthWidth) }
    val eyeStyle = remember { Animatable(target.eyeStyle) }
    val blink = remember { Animatable(1f) }

    LaunchedEffect(mood) {
        launch { eyeOpen.animateTo(target.eyeOpen, PoseSpring) }
        launch { eyeWidth.animateTo(target.eyeWidth, PoseSpring) }
        launch { eyeHeight.animateTo(target.eyeHeight, PoseSpring) }
        launch { eyeGap.animateTo(target.eyeGap, PoseSpring) }
        launch { tilt.animateTo(target.tilt, PoseSpring) }
        launch { borderGlow.animateTo(target.borderGlow, PoseSpring) }
        launch { blush.animateTo(target.blush, PoseSpring) }
        launch { mouthCurve.animateTo(target.mouthCurve, PoseSpring) }
        launch { mouthWidth.animateTo(target.mouthWidth, PoseSpring) }
        launch { eyeStyle.animateTo(target.eyeStyle, PoseSpring) }
        if (mood != AssistantMood.Speaking) {
            launch { mouthOpen.animateTo(target.mouthOpen, PoseSpring) }
        }
        if (mood != AssistantMood.Reading && mood != AssistantMood.Searching) {
            launch { lookX.animateTo(target.lookX, PoseSpring) }
        }
        launch { lookY.animateTo(target.lookY, PoseSpring) }
    }

    LaunchedEffect(mood) {
        if (mood != AssistantMood.Speaking) return@LaunchedEffect
        while (isActive) {
            mouthOpen.animateTo(
                Random.nextFloat() * 0.35f + 0.35f,
                tween(Random.nextInt(80, 140)),
            )
            mouthOpen.animateTo(
                Random.nextFloat() * 0.1f + 0.04f,
                tween(Random.nextInt(60, 110)),
            )
        }
    }

    val infinite = rememberInfiniteTransition(label = "nomi_orb")
    val life by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "life",
    )
    val breath by infinite.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    LaunchedEffect(mood) {
        while (isActive) {
            delay(Random.nextLong(2200, 4200))
            if (eyeStyle.value > 0.6f) continue // happy arcs don't blink the same way
            blink.animateTo(0.12f, tween(70))
            delay(40)
            blink.animateTo(1f, tween(120))
            if (Random.nextFloat() < 0.25f) {
                delay(90)
                blink.animateTo(0.12f, tween(55))
                delay(30)
                blink.animateTo(1f, tween(110))
            }
        }
    }

    LaunchedEffect(mood) {
        if (mood != AssistantMood.Reading && mood != AssistantMood.Searching) return@LaunchedEffect
        while (isActive) {
            if (mood == AssistantMood.Reading) {
                lookX.animateTo(0.35f, tween(800))
                delay(120)
                lookX.animateTo(-0.3f, tween(100))
                delay(80)
            } else {
                lookX.animateTo(0.4f, tween(180))
                lookX.animateTo(-0.35f, tween(220))
                lookX.animateTo(0.05f, tween(150))
                delay(60)
            }
        }
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val side = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val shellR = side * 0.42f * breath
        val faceR = shellR * faceFillRatio.coerceIn(0.5f, 0.995f)
        val r = faceR
        val moodTint = mood.glowColor
        val g = borderGlow.value

        val bob = sin(life * 0.55f).toFloat() * shellR * 0.025f
        translate(top = bob) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        moodTint.copy(alpha = 0.35f * g),
                        moodTint.copy(alpha = 0.08f * g),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy + shellR * 0.06f),
                    radius = shellR * 1.55f,
                ),
                radius = shellR * 1.55f,
                center = Offset(cx, cy + shellR * 0.06f),
            )

            val liveTilt = tilt.value + 0.6f * sin(life * 0.35f).toFloat()
            rotate(liveTilt, pivot = Offset(cx, cy)) {
                // Pale thin rim + matte black face (NIO Day / icon pack)
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFE8ECF2),
                            Color(0xFF9AA3B2),
                            Color(0xFF5A6270),
                            Color(0xFFC5CAD4),
                        ),
                        start = Offset(cx - shellR, cy - shellR),
                        end = Offset(cx + shellR, cy + shellR),
                    ),
                    radius = shellR,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF141418), OrbCore, Color(0xFF000000)),
                        center = Offset(cx - faceR * 0.15f, cy - faceR * 0.2f),
                        radius = faceR * 1.25f,
                    ),
                    radius = faceR,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(cx - faceR * 0.25f, cy - faceR * 0.4f),
                        radius = faceR * 0.55f,
                    ),
                    radius = faceR * 0.55f,
                    center = Offset(cx - faceR * 0.25f, cy - faceR * 0.4f),
                )

                val open = (eyeOpen.value * blink.value).coerceIn(0.08f, 1.3f)
                val eW = r * 0.075f * eyeWidth.value
                val eH = r * 0.2f * eyeHeight.value * open
                val gap = r * 0.34f * eyeGap.value.coerceAtLeast(1.2f)
                val eyeY = cy - r * 0.06f + lookY.value * r * 0.1f
                val gaze = lookX.value * r * 0.06f
                val left = Offset(cx - gap + gaze, eyeY)
                val right = Offset(cx + gap + gaze, eyeY)

                if (blush.value > 0.05f) {
                    val a = 0.22f * blush.value
                    drawCircle(Color(0xFFFF8FA8).copy(alpha = a), r * 0.1f, Offset(cx - gap * 0.95f, cy + r * 0.22f))
                    drawCircle(Color(0xFFFF8FA8).copy(alpha = a), r * 0.1f, Offset(cx + gap * 0.95f, cy + r * 0.22f))
                }

                drawNomiEye(left, eW, eH, eyeStyle.value, faceColor)
                drawNomiEye(right, eW, eH, eyeStyle.value, faceColor)

                drawNomiMouth(
                    center = Offset(cx, cy + r * 0.34f),
                    faceR = r,
                    curve = mouthCurve.value,
                    open = mouthOpen.value,
                    widthScale = mouthWidth.value,
                    color = faceColor,
                    speaking = mood == AssistantMood.Speaking,
                    life = life,
                )
            }
        }
    }
}

private fun DrawScope.drawNomiEye(
    center: Offset,
    width: Float,
    height: Float,
    style: Float,
    color: Color,
) {
    when {
        style > 0.35f -> {
            // Happy ^ arcs
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
            // Sleepy / flat dashes
            val w = width * 1.4f
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
            // Classic NOMI capsules (vertical pills)
            val w = width
            val h = height.coerceAtLeast(w * 1.1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - w, center.y - h),
                size = Size(w * 2f, h * 2f),
                cornerRadius = CornerRadius(w, w),
            )
            // Soft inner glow
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(center.x - w * 0.55f, center.y - h * 0.7f),
                size = Size(w * 0.7f, h * 0.55f),
                cornerRadius = CornerRadius(w * 0.4f, w * 0.4f),
            )
        }
    }
}

private fun DrawScope.drawNomiMouth(
    center: Offset,
    faceR: Float,
    curve: Float,
    open: Float,
    widthScale: Float,
    color: Color,
    speaking: Boolean,
    life: Float,
) {
    val halfW = faceR * 0.16f * widthScale
    val smile = faceR * 0.07f * curve
    val openH = faceR * 0.07f * open.coerceIn(0f, 1f)
    val wobble = if (speaking) sin(life * 3.2f).toFloat() * faceR * 0.01f else 0f

    if (openH > faceR * 0.015f) {
        val w = halfW * 1.2f
        val h = openH * 1.35f
        drawRoundRect(
            color = color,
            topLeft = Offset(center.x - w * 0.5f, center.y - h * 0.3f + wobble),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.5f, h * 0.5f),
        )
    } else if (kotlin.math.abs(curve) > 0.12f) {
        val path = Path().apply {
            val y0 = center.y + wobble
            moveTo(center.x - halfW, y0)
            quadraticTo(center.x, y0 + smile, center.x + halfW, y0)
        }
        drawPath(
            path,
            color.copy(alpha = 0.95f),
            style = Stroke(width = faceR * 0.035f, cap = StrokeCap.Round),
        )
    }
}

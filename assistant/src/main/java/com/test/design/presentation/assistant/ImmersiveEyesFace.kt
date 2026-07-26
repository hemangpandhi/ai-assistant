package com.test.design.presentation.assistant

import android.graphics.BlurMaskFilter
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Matte black face fill (NOMI-like). */
internal val NomiFaceBlack = Color(0xFF050508)

/**
 * NIO NOMI–style glyphs: hollow ring eyes + mouth on a matte black SemiCircle face.
 */
internal data class ImmersiveEyePose(
    val eyeOpen: Float = 1f,
    val eyeWidth: Float = 1f,
    val eyeHeight: Float = 1f,
    /** Half-distance scale — higher = wider set (Nomi-cute). */
    val eyeGap: Float = 1.45f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val tilt: Float = 0f,
    val faceGlow: Float = 0.7f,
    val eyeStyle: Float = 0f,
    val mouthCurve: Float = 0.15f,
    val mouthOpen: Float = 0f,
    val mouthVisible: Float = 0f,
    val blinkSpeed: Float = 1f,
    /** Soft cheek glow 0..1 */
    val blush: Float = 0f,
)

/**
 * Shared capsule eye/mouth persona. Moods morph geometry (openness, squint, gaze, mouth)
 * — eyeStyle stays in the soft-capsule range so glyphs never swap to arcs/dashes.
 */
private val PersonaBase = ImmersiveEyePose(
    eyeOpen = 1.0f,
    eyeWidth = 1.12f,
    eyeHeight = 0.92f,
    eyeGap = 1.48f,
    eyeStyle = 0.05f,
    faceGlow = 0.55f,
    mouthCurve = 0.2f,
    mouthOpen = 0f,
    mouthVisible = 0.35f,
    blush = 0.08f,
    tilt = 0f,
    blinkSpeed = 0.9f,
)

internal fun AssistantMood.toImmersiveEyePose(): ImmersiveEyePose = when (this) {
    AssistantMood.Idle -> PersonaBase
    AssistantMood.Listening -> PersonaBase.copy(
        eyeOpen = 1.08f,
        eyeWidth = 1.12f,
        eyeHeight = 0.98f,
        faceGlow = 0.72f,
        mouthVisible = 0.12f,
        lookY = -0.05f,
        blush = 0.12f,
    )
    AssistantMood.Speaking -> PersonaBase.copy(
        eyeOpen = 1.05f,
        mouthCurve = 0.42f,
        mouthOpen = 0.45f,
        mouthVisible = 1f,
        faceGlow = 0.7f,
        tilt = 1f,
    )
    AssistantMood.Thinking -> PersonaBase.copy(
        eyeOpen = 0.98f,
        eyeHeight = 0.90f,
        lookX = 0.22f,
        lookY = -0.08f,
        mouthVisible = 0.1f,
        tilt = 4f,
        faceGlow = 0.58f,
    )
    AssistantMood.Reading -> PersonaBase.copy(
        eyeOpen = 1.0f,
        lookX = 0.28f,
        mouthVisible = 0.08f,
        faceGlow = 0.55f,
    )
    AssistantMood.Searching -> PersonaBase.copy(
        eyeOpen = 1.06f,
        eyeHeight = 0.95f,
        mouthVisible = 0.1f,
        faceGlow = 0.68f,
        tilt = 1.5f,
        blinkSpeed = 1.15f,
    )
    AssistantMood.Happy -> PersonaBase.copy(
        eyeOpen = 0.98f,
        eyeWidth = 1.22f,
        eyeHeight = 0.82f,
        mouthCurve = 0.85f,
        mouthOpen = 0.08f,
        mouthVisible = 0.95f,
        blush = 0.45f,
        faceGlow = 0.78f,
        tilt = -2f,
    )
    AssistantMood.Excited -> PersonaBase.copy(
        eyeOpen = 1.1f,
        eyeWidth = 1.18f,
        eyeHeight = 1.0f,
        mouthCurve = 0.92f,
        mouthOpen = 0.35f,
        mouthVisible = 1f,
        blush = 0.35f,
        faceGlow = 0.85f,
        tilt = -3f,
        blinkSpeed = 1.25f,
    )
    AssistantMood.Sad -> PersonaBase.copy(
        eyeOpen = 0.85f,
        eyeWidth = 1.15f,
        eyeHeight = 0.88f,
        lookY = 0.14f,
        mouthCurve = -0.7f,
        mouthOpen = 0.04f,
        mouthVisible = 0.75f,
        faceGlow = 0.4f,
        tilt = 3.5f,
        blinkSpeed = 0.7f,
    )
    AssistantMood.Bored -> PersonaBase.copy(
        eyeOpen = 0.72f,
        eyeWidth = 1.18f,
        eyeHeight = 0.72f,
        lookX = 0.32f,
        lookY = 0.06f,
        mouthCurve = -0.12f,
        mouthVisible = 0.4f,
        faceGlow = 0.38f,
        tilt = 2.5f,
        blinkSpeed = 0.55f,
    )
    AssistantMood.Drowsy -> PersonaBase.copy(
        eyeOpen = 0.55f,
        eyeWidth = 1.2f,
        eyeHeight = 0.58f,
        lookY = 0.1f,
        mouthVisible = 0.08f,
        faceGlow = 0.32f,
        tilt = 2f,
        blinkSpeed = 0.4f,
    )
    AssistantMood.Tired -> PersonaBase.copy(
        eyeOpen = 0.62f,
        eyeWidth = 1.16f,
        eyeHeight = 0.65f,
        lookY = 0.12f,
        mouthCurve = -0.25f,
        mouthVisible = 0.35f,
        faceGlow = 0.3f,
        tilt = 2.5f,
        blinkSpeed = 0.38f,
    )
}

private val PoseSpring = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessLow,
)

/**
 * Floating Nomi glyphs on a matte black SemiCircle face.
 *
 * @param gazeX/gazeY optional cabin gaze override (−1..1); null keeps mood look loops
 * @param mouthAmplitude optional lip-sync 0..1 (drives mouth while speaking)
 * @param brandGlow OEM / Material accent for soft pulse / parallax halo
 * @param highContrast sunlight-safe glyph rendering
 * @param gesture nod / shake micro-expressions for yes/no
 * @param eyeGlow when non-null, capsule eyes use this EPORO-style purple glow ring
 *   (same shape / blink / morph as Immersive — only the ring tint + bloom change)
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
fun ImmersiveEyesFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = Color(0xFF8AB4F8),
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    eyeGlow: Color? = null,
) {
    val target = mood.toImmersiveEyePose()
    // Fixed SemiCircle shell — matte black face fill.
    val shellMorph = remember {
        ExpressiveShellMorphState(
            morph = Morph(
                start = ExpressiveShellKind.SemiCircle.toRoundedPolygon(),
                end = ExpressiveShellKind.SemiCircle.toRoundedPolygon(),
            ),
            progress = 1f,
        )
    }
    val eyeOpen = remember { Animatable(target.eyeOpen) }
    val eyeWidth = remember { Animatable(target.eyeWidth) }
    val eyeHeight = remember { Animatable(target.eyeHeight) }
    val eyeGap = remember { Animatable(target.eyeGap) }
    val lookX = remember { Animatable(target.lookX) }
    val lookY = remember { Animatable(target.lookY) }
    val tilt = remember { Animatable(target.tilt) }
    val faceGlow = remember { Animatable(target.faceGlow) }
    val eyeStyle = remember { Animatable(target.eyeStyle) }
    val mouthCurve = remember { Animatable(target.mouthCurve) }
    val mouthOpen = remember { Animatable(target.mouthOpen) }
    val mouthVisible = remember { Animatable(target.mouthVisible) }
    val blush = remember { Animatable(target.blush) }
    val blink = remember { Animatable(1f) }
    val externalGaze = gazeX != null || gazeY != null

    LaunchedEffect(mood, highContrast) {
        val glowBoost = if (highContrast) 1.25f else 1f
        launch { eyeOpen.animateTo(target.eyeOpen, PoseSpring) }
        launch { eyeWidth.animateTo(target.eyeWidth, PoseSpring) }
        launch { eyeHeight.animateTo(target.eyeHeight, PoseSpring) }
        launch { eyeGap.animateTo(target.eyeGap, PoseSpring) }
        launch { lookY.animateTo(gazeY ?: target.lookY, PoseSpring) }
        launch { tilt.animateTo(target.tilt, PoseSpring) }
        launch { faceGlow.animateTo((target.faceGlow * glowBoost).coerceAtMost(1.2f), PoseSpring) }
        launch { eyeStyle.animateTo(target.eyeStyle, PoseSpring) }
        launch { mouthCurve.animateTo(target.mouthCurve, PoseSpring) }
        launch { mouthVisible.animateTo(target.mouthVisible, PoseSpring) }
        launch { blush.animateTo(target.blush, PoseSpring) }
        if (mouthAmplitude == null &&
            mood != AssistantMood.Speaking &&
            mood != AssistantMood.Excited
        ) {
            launch { mouthOpen.animateTo(target.mouthOpen, PoseSpring) }
        }
        if (!externalGaze &&
            mood != AssistantMood.Reading &&
            mood != AssistantMood.Searching &&
            mood != AssistantMood.Bored
        ) {
            launch { lookX.animateTo(target.lookX, PoseSpring) }
        }
    }

    LaunchedEffect(gazeX, gazeY) {
        if (gazeX != null) lookX.animateTo(gazeX, PoseSpring)
        if (gazeY != null) lookY.animateTo(gazeY, PoseSpring)
    }

    LaunchedEffect(mouthAmplitude, mood) {
        if (mouthAmplitude != null) {
            mouthVisible.animateTo(maxOf(target.mouthVisible, 0.85f), PoseSpring)
            mouthOpen.snapTo(mouthAmplitude.coerceIn(0f, 1f))
            return@LaunchedEffect
        }
        if (mood != AssistantMood.Speaking && mood != AssistantMood.Excited) return@LaunchedEffect
        while (isActive) {
            mouthOpen.animateTo(
                Random.nextFloat() * 0.4f + 0.3f,
                tween(Random.nextInt(70, 130)),
            )
            mouthOpen.animateTo(
                Random.nextFloat() * 0.12f + 0.04f,
                tween(Random.nextInt(55, 100)),
            )
        }
    }

    LaunchedEffect(gesture) {
        when (gesture) {
            FaceGesture.None -> Unit
            FaceGesture.Nod -> {
                repeat(2) {
                    tilt.animateTo(target.tilt + 10f, tween(120))
                    tilt.animateTo(target.tilt - 4f, tween(120))
                }
                tilt.animateTo(target.tilt, PoseSpring)
            }
            FaceGesture.Shake -> {
                repeat(2) {
                    lookX.animateTo(0.55f, tween(100))
                    lookX.animateTo(-0.55f, tween(100))
                }
                lookX.animateTo(gazeX ?: target.lookX, PoseSpring)
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "immersive_eyes")
    val life by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "life",
    )
    val breath by infinite.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    // Gentle idle float — slow vertical bob + tiny lateral sway.
    val idleBob by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle_bob",
    )
    val idleSway by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle_sway",
    )
    // Soft activity halo — gentle pulse suggests the persona is live.
    val activityPulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "activity_pulse",
    )

    LaunchedEffect(mood) {
        val speed = target.blinkSpeed.coerceIn(0.25f, 1.6f)
        while (isActive) {
            val base = when (mood) {
                AssistantMood.Drowsy, AssistantMood.Tired -> Random.nextLong(900, 1800)
                AssistantMood.Bored -> Random.nextLong(2800, 4800)
                AssistantMood.Excited, AssistantMood.Listening -> Random.nextLong(1800, 3200)
                else -> Random.nextLong(2200, 4000)
            }
            delay((base / speed).toLong().coerceAtLeast(400L))
            if (eyeStyle.value > 0.7f) continue
            val closeTo = when (mood) {
                AssistantMood.Drowsy -> 0.06f
                AssistantMood.Tired -> 0.08f
                else -> 0.12f
            }
            blink.animateTo(closeTo, tween((90 / speed).toInt().coerceAtLeast(40)))
            delay((50 / speed).toLong().coerceAtLeast(20L))
            blink.animateTo(1f, tween((140 / speed).toInt().coerceAtLeast(60)))
            if (mood == AssistantMood.Tired || mood == AssistantMood.Drowsy) {
                delay(120)
                blink.animateTo(closeTo * 1.4f, tween(80))
                delay(40)
                blink.animateTo(1f, tween(160))
            }
        }
    }

    LaunchedEffect(mood, externalGaze) {
        if (externalGaze) return@LaunchedEffect
        if (mood != AssistantMood.Reading &&
            mood != AssistantMood.Searching &&
            mood != AssistantMood.Bored
        ) {
            return@LaunchedEffect
        }
        while (isActive) {
            when (mood) {
                AssistantMood.Reading -> {
                    lookX.animateTo(0.38f, tween(700))
                    delay(100)
                    lookX.animateTo(-0.32f, tween(90))
                    delay(70)
                }
                AssistantMood.Searching -> {
                    lookX.animateTo(0.45f, tween(160))
                    lookX.animateTo(-0.4f, tween(200))
                    lookX.animateTo(0.08f, tween(140))
                    delay(50)
                }
                AssistantMood.Bored -> {
                    lookX.animateTo(0.5f, tween(1600, easing = FastOutSlowInEasing))
                    delay(600)
                    lookX.animateTo(-0.35f, tween(1800, easing = FastOutSlowInEasing))
                    delay(800)
                }
                else -> delay(500)
            }
        }
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val side = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        // Feature scale only — no drawn shell / black disk.
        val faceR = side * 0.36f * breath
        val glyph = eyeFillForContrast(highContrast)
        // [eyeGlow].alpha drives a continuous pale↔purple morph (Immersive hybrid).
        val glowAmount = eyeGlow?.alpha?.coerceIn(0f, 1f) ?: 0f
        val glowBase = when {
            eyeGlow == null -> glyph
            highContrast -> eyeFillForContrast(true)
            else -> eyeGlow.copy(alpha = 1f)
        }
        val eyeRing = lerp(glyph, glowBase, glowAmount)
        val glow = faceGlow.value.coerceIn(0f, 1.2f)
        val bobY = idleBob * faceR * 0.058f
        val swayX = idleSway * faceR * 0.02f
        val pulse = activityPulse.coerceIn(0f, 1f)

        // Soft pulsing halo behind the character — faint activity cue.
        val pulseCenter = Offset(cx + swayX * 0.25f, cy + bobY * 0.25f)
        val pulseA = auraAlphaForContrast(highContrast, 0.08f) * glow *
            (0.45f + 0.55f * pulse)
        val pulseR = faceR * (2.15f + 0.22f * pulse)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to brandGlow.copy(alpha = pulseA * 0.16f),
                    0.45f to brandGlow.copy(alpha = pulseA * 0.06f),
                    0.80f to brandGlow.copy(alpha = pulseA * 0.02f),
                    1.0f to Color.Transparent,
                ),
                center = pulseCenter,
                radius = pulseR,
            ),
            radius = pulseR,
            center = pulseCenter,
        )

        // Very light parallax halo — drifts opposite bob/gaze for a soft depth cue.
        val parallaxX = -lookX.value * faceR * 0.045f - swayX * 0.55f +
            sin(life * 0.22f).toFloat() * faceR * 0.016f
        val parallaxY = -bobY * 0.65f - lookY.value * faceR * 0.035f +
            cos(life * 0.19f).toFloat() * faceR * 0.012f
        val haloA = auraAlphaForContrast(highContrast, 0.06f) * glow
        val haloR = faceR * (2.25f + 0.05f * sin(life * 0.5f).toFloat())
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to brandGlow.copy(alpha = haloA * 0.18f),
                    0.50f to brandGlow.copy(alpha = haloA * 0.05f),
                    1.0f to Color.Transparent,
                ),
                center = Offset(cx + parallaxX, cy + parallaxY),
                radius = haloR,
            ),
            radius = haloR,
            center = Offset(cx + parallaxX, cy + parallaxY),
        )

        // Faint floor shadow — flat puddle that barely follows motion so the face feels anchored.
        val floorCx = cx + swayX * 0.3f
        val floorCy = cy + faceR * 0.78f + bobY * 0.18f
        val floorW = faceR * 1.48f
        val floorH = faceR * 0.20f
        drawOval(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color.Black.copy(alpha = 0.30f),
                    0.40f to Color.Black.copy(alpha = 0.14f),
                    0.75f to Color.Black.copy(alpha = 0.04f),
                    1.0f to Color.Transparent,
                ),
                center = Offset(floorCx, floorCy),
                radius = floorW,
            ),
            topLeft = Offset(floorCx - floorW, floorCy - floorH),
            size = Size(floorW * 2f, floorH * 2f),
        )

        translate(left = swayX, top = bobY) {
            // Fixed SemiCircle silhouette — tall chin clearance for open / speaking mouths.
            val shellW = faceR * 1.38f
            val shellH = faceR * 1.42f
            val shellBounds = Rect(
                left = cx - shellW,
                top = cy - shellH * 0.68f,
                right = cx + shellW,
                bottom = cy + shellH * 0.72f,
            )

            // Matte black SemiCircle face (color only — shape unchanged).
            drawExpressiveFaceShell(
                morphState = shellMorph,
                bounds = shellBounds,
                color = NomiFaceBlack,
            )
            // Soft top-left sheen on the black shell.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                    center = Offset(cx - faceR * 0.28f, cy - faceR * 0.32f),
                    radius = faceR * 0.72f,
                ),
                radius = faceR * 0.72f,
                center = Offset(cx - faceR * 0.28f, cy - faceR * 0.32f),
            )
            // Hairline pale rim stroke.
            val rimAlpha = auraAlphaForContrast(highContrast, 0.28f) * glow.coerceIn(0.35f, 1f)
            drawExpressiveFaceShell(
                morphState = shellMorph,
                bounds = shellBounds,
                color = Color(0xFFE8ECF2).copy(alpha = rimAlpha * 0.65f),
                style = Stroke(width = 0.028f, cap = StrokeCap.Round),
            )

            val liveTilt = tilt.value + 0.35f * sin(life * 0.28f).toFloat()
            rotate(liveTilt, pivot = Offset(cx, cy)) {
                val open = (eyeOpen.value * blink.value).coerceIn(0.05f, 1.12f)
                val gap = faceR * 0.36f * eyeGap.value.coerceIn(1f, 1.8f)
                val eyeY = cy - faceR * 0.06f + lookY.value * faceR * 0.1f
                val gaze = lookX.value * faceR * 0.06f
                // Shorter capsules — capped height while animating.
                val barW = faceR * 0.11f * eyeWidth.value.coerceIn(0.8f, 1.35f)
                val barH = (faceR * 0.22f * eyeHeight.value.coerceAtMost(1.05f) * open)
                    .coerceAtMost(faceR * 0.26f)
                val left = Offset(cx - gap + gaze, eyeY)
                val right = Offset(cx + gap + gaze, eyeY)

                if (blush.value > 0.04f) {
                    val blushA = 0.2f * blush.value
                    val bx = gap * 0.95f
                    drawCircle(
                        Color(0xFFFF9BB0).copy(alpha = blushA),
                        faceR * 0.1f,
                        Offset(cx - bx, cy + faceR * 0.22f),
                    )
                    drawCircle(
                        Color(0xFFFF9BB0).copy(alpha = blushA),
                        faceR * 0.1f,
                        Offset(cx + bx, cy + faceR * 0.22f),
                    )
                }

                // Keep capsule eyes for all moods (clamp out of arc/dash branches).
                val capsuleStyle = eyeStyle.value.coerceIn(-0.2f, 0.25f)
                drawNomiGlyphEye(left, barW, barH, capsuleStyle, eyeRing, glowStrength = glowAmount)
                drawNomiGlyphEye(right, barW, barH, capsuleStyle, eyeRing, glowStrength = glowAmount)

                val speaking = mouthAmplitude != null ||
                    mood == AssistantMood.Speaking ||
                    mood == AssistantMood.Excited
                if (mouthVisible.value > 0.08f || (mouthAmplitude != null && mouthAmplitude > 0.05f)) {
                    drawNomiGlyphMouth(
                        center = Offset(cx, cy + faceR * 0.38f),
                        faceR = faceR,
                        curve = mouthCurve.value,
                        open = mouthOpen.value,
                        visible = maxOf(mouthVisible.value, if (mouthAmplitude != null) 0.9f else 0f),
                        color = glyph,
                        speaking = speaking,
                        life = life,
                    )
                }
            }
        }
    }
}

/** Immersive capsule eyes — [glowStrength] 0..1 morphs pale rings ↔ EPORO purple bloom. */
internal fun DrawScope.drawNomiGlyphEye(
    center: Offset,
    width: Float,
    height: Float,
    style: Float,
    color: Color,
    glowRing: Boolean = false,
    glowStrength: Float = if (glowRing) 1f else 0f,
) {
    val w = width.coerceAtLeast(1.5f)
    // Allow short capsules — do not force taller-than-wide.
    val h = height.coerceAtLeast(w * 0.55f)
    val gs = glowStrength.coerceIn(0f, 1f)
    when {
        style > 0.28f -> {
            // Cute ^ happy arcs (icon-pack Nomi)
            val path = Path().apply {
                moveTo(center.x - w * 1.85f, center.y + h * 0.25f)
                quadraticTo(
                    center.x,
                    center.y - h * (0.65f + 0.45f * style),
                    center.x + w * 1.85f,
                    center.y + h * 0.25f,
                )
            }
            if (gs > 0.02f) {
                drawIntoCanvas { canvas ->
                    val fw = Paint().asFrameworkPaint()
                    fw.isAntiAlias = true
                    fw.color = color.copy(alpha = 0.4f * gs).toArgb()
                    fw.maskFilter = BlurMaskFilter(w * 1.1f, BlurMaskFilter.Blur.NORMAL)
                    fw.strokeWidth = w * 1.45f
                    fw.strokeCap = android.graphics.Paint.Cap.ROUND
                    fw.style = android.graphics.Paint.Style.STROKE
                    canvas.nativeCanvas.drawPath(
                        android.graphics.Path().apply {
                            moveTo(center.x - w * 1.85f, center.y + h * 0.25f)
                            quadTo(
                                center.x,
                                center.y - h * (0.65f + 0.45f * style),
                                center.x + w * 1.85f,
                                center.y + h * 0.25f,
                            )
                        },
                        fw,
                    )
                }
            }
            drawPath(path, color, style = Stroke(width = w * 1.45f, cap = StrokeCap.Round))
        }
        style < -0.25f -> {
            // Soft sleepy dashes — still two distinct eyes, not one bar
            val flatten = (-style).coerceIn(0.25f, 1f)
            val dashW = w * (1.6f + 0.5f * flatten)
            val dashH = (h * (1f - 0.55f * flatten)).coerceAtLeast(w * 0.95f)
            if (gs > 0.02f) {
                val bloomArgb = color.copy(alpha = 0.4f * gs).toArgb()
                drawIntoCanvas { canvas ->
                    val paint = Paint().asFrameworkPaint().apply {
                        isAntiAlias = true
                        this.color = bloomArgb
                        maskFilter = BlurMaskFilter(dashW * 0.55f, BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawOval(
                        center.x - dashW * 1.35f,
                        center.y - dashH * 0.85f,
                        center.x + dashW * 1.35f,
                        center.y + dashH * 0.85f,
                        paint,
                    )
                }
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - dashW, center.y - dashH * 0.5f),
                size = Size(dashW * 2f, dashH),
                cornerRadius = CornerRadius(dashH, dashH),
            )
        }
        else -> {
            // Hollow pill-in-pill — Immersive capsule shape; optional EPORO purple bloom.
            val bloomR = maxOf(w, h) * 1.85f
            if (gs > 0.02f) {
                val bloomArgb = color.copy(alpha = 0.45f * gs).toArgb()
                drawIntoCanvas { canvas ->
                    val paint = Paint().asFrameworkPaint().apply {
                        isAntiAlias = true
                        this.color = bloomArgb
                        maskFilter = BlurMaskFilter(bloomR * 0.48f, BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawOval(
                        center.x - w * 1.55f,
                        center.y - h * 1.55f,
                        center.x + w * 1.55f,
                        center.y + h * 1.55f,
                        paint,
                    )
                }
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.35f * gs), Color.Transparent),
                        center = center,
                        radius = bloomR,
                    ),
                    topLeft = Offset(center.x - bloomR, center.y - bloomR),
                    size = Size(bloomR * 2f, bloomR * 2f),
                )
            } else {
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = bloomR,
                    ),
                    topLeft = Offset(center.x - bloomR, center.y - bloomR),
                    size = Size(bloomR * 2f, bloomR * 2f),
                )
            }
            val strokeW = minOf(w, h) * 0.42f
            drawRoundRect(
                color = color.copy(alpha = 0.98f),
                topLeft = Offset(center.x - w, center.y - h),
                size = Size(w * 2f, h * 2f),
                cornerRadius = CornerRadius(w, w),
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            val innerW = w * 0.52f
            val innerH = h * 0.52f
            drawRoundRect(
                color = NomiFaceBlack,
                topLeft = Offset(center.x - innerW, center.y - innerH),
                size = Size(innerW * 2f, innerH * 2f),
                cornerRadius = CornerRadius(innerW, innerW),
            )
            // Specular on the lower ring arc.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f + 0.35f * gs),
                topLeft = Offset(center.x - w * 0.18f, center.y + h * 0.52f),
                size = Size(w * 0.36f, h * 0.14f),
                cornerRadius = CornerRadius(w * 0.2f, w * 0.2f),
            )
        }
    }
}

/**
 * Immersive face + EPORO / Fusion purple glow rings on the same capsule eyes.
 * Shell, mouth, blink, gaze, and mood morphs are identical to [ImmersiveEyesFace].
 */
@Composable
fun ImmersiveGlowEyesFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = EporoGlow,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    eyeGlow: Color = EporoGlow,
) {
    ImmersiveEyesFace(
        mood = mood,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        eyeGlow = eyeGlow,
    )
}

/** Excited / Happy / Searching / Thinking → purple glow rings; other moods → pale eyes. */
fun AssistantMood.usesImmersivePurpleGlow(): Boolean = when (this) {
    AssistantMood.Excited,
    AssistantMood.Happy,
    AssistantMood.Searching,
    AssistantMood.Thinking,
    -> true
    else -> false
}

/**
 * Mood-aware merge of Immersive glow + Immersive eyes.
 * One face instance — purple bloom strength and mood poses morph continuously
 * so any conversation state can transition smoothly into any other.
 */
@Composable
fun ImmersiveHybridEyesFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = EporoGlow,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
) {
    val glowStrength = remember {
        Animatable(if (mood.usesImmersivePurpleGlow()) 1f else 0f)
    }
    LaunchedEffect(mood) {
        glowStrength.animateTo(
            targetValue = if (mood.usesImmersivePurpleGlow()) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.92f,
                stiffness = 90f,
            ),
        )
    }

    ImmersiveEyesFace(
        mood = mood,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        // Always pass a glow color; alpha is the morph amount (0 = pale, 1 = purple).
        eyeGlow = EporoGlow.copy(alpha = glowStrength.value.coerceIn(0f, 1f)),
    )
}

private fun DrawScope.drawNomiGlyphMouth(
    center: Offset,
    faceR: Float,
    curve: Float,
    open: Float,
    visible: Float,
    color: Color,
    speaking: Boolean,
    life: Float,
) {
    val alpha = visible.coerceIn(0f, 1f)
    val halfW = faceR * 0.15f
    val smile = faceR * 0.065f * curve
    val openH = faceR * 0.06f * open.coerceIn(0f, 1f)
    val wobble = if (speaking) sin(life * 3.4f).toFloat() * faceR * 0.01f else 0f
    val tint = color.copy(alpha = 0.95f * alpha)

    if (openH > faceR * 0.014f) {
        val w = halfW * 1.1f
        val h = openH * 1.3f
        drawRoundRect(
            color = tint,
            topLeft = Offset(center.x - w * 0.5f, center.y - h * 0.3f + wobble),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.5f, h * 0.5f),
        )
    } else if (abs(curve) > 0.1f) {
        val path = Path().apply {
            val y0 = center.y + wobble
            moveTo(center.x - halfW, y0)
            quadraticTo(center.x, y0 + smile, center.x + halfW, y0)
        }
        drawPath(path, tint, style = Stroke(width = faceR * 0.034f, cap = StrokeCap.Round))
    }
}

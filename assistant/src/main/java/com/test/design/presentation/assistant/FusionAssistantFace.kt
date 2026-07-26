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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.graphics.shapes.Morph
import kotlin.math.min
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val PoseSpring = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessLow,
)

/**
 * Fusion expression map — more dramatic eye morphs + spacing than Immersive,
 * mouth only for clear emotional / speaking states.
 */
internal data class FusionEyePose(
    val eyeOpen: Float = 1f,
    val eyeWidth: Float = 1f,
    val eyeHeight: Float = 1f,
    /** Half-distance scale — higher = farther apart. */
    val eyeGap: Float = 1f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val tilt: Float = 0f,
    val faceGlow: Float = 0.7f,
    /** >0.35 happy arcs · <−0.25 sleepy dashes · else morphing glow rings. */
    val eyeStyle: Float = 0f,
    val mouthCurve: Float = 0f,
    val mouthOpen: Float = 0f,
    val mouthVisible: Float = 0f,
    val blinkSpeed: Float = 1f,
    val blush: Float = 0f,
)

private val FusionBase = FusionEyePose(
    eyeOpen = 1.0f,
    eyeWidth = 1.05f,
    eyeHeight = 1.0f,
    eyeGap = 1.0f,
    eyeStyle = 0f,
    faceGlow = 0.55f,
    mouthCurve = 0f,
    mouthOpen = 0f,
    mouthVisible = 0f,
    blush = 0f,
    tilt = 0f,
    blinkSpeed = 0.9f,
)

internal fun AssistantMood.toFusionEyePose(): FusionEyePose = when (this) {
    AssistantMood.Idle -> FusionBase
    AssistantMood.Listening -> FusionBase.copy(
        eyeOpen = 1.12f,
        eyeWidth = 1.08f,
        eyeHeight = 1.12f,
        eyeGap = 1.18f, // alert — eyes farther
        faceGlow = 0.78f,
        lookY = -0.06f,
        blush = 0.1f,
        blinkSpeed = 1.1f,
    )
    AssistantMood.Speaking -> FusionBase.copy(
        eyeOpen = 1.06f,
        eyeWidth = 1.1f,
        eyeHeight = 1.02f,
        eyeGap = 1.06f,
        mouthCurve = 0.45f,
        mouthOpen = 0.48f,
        mouthVisible = 1f,
        faceGlow = 0.72f,
        tilt = 1f,
    )
    AssistantMood.Thinking -> FusionBase.copy(
        eyeOpen = 0.96f,
        eyeWidth = 1.02f,
        eyeHeight = 0.88f,
        eyeGap = 0.82f, // focused — eyes closer
        lookX = 0.24f,
        lookY = -0.1f,
        tilt = 5f,
        faceGlow = 0.58f,
    )
    AssistantMood.Reading -> FusionBase.copy(
        eyeOpen = 1.0f,
        eyeWidth = 1.14f,
        eyeHeight = 0.86f,
        eyeGap = 0.88f,
        lookX = 0.3f,
        lookY = 0.06f,
        faceGlow = 0.52f,
    )
    AssistantMood.Searching -> FusionBase.copy(
        eyeOpen = 1.1f,
        eyeWidth = 1.06f,
        eyeHeight = 1.08f,
        eyeGap = 1.22f, // scanning — wide set
        faceGlow = 0.7f,
        tilt = 1.5f,
        blinkSpeed = 1.2f,
    )
    AssistantMood.Happy -> FusionBase.copy(
        eyeOpen = 0.96f,
        eyeWidth = 1.16f,
        eyeHeight = 0.82f, // soft squint — still glow rings, not full arcs
        eyeGap = 1.06f,
        eyeStyle = 0.12f,
        mouthCurve = 0.55f,
        mouthOpen = 0.04f,
        mouthVisible = 0.75f,
        blush = 0.28f,
        faceGlow = 0.72f,
        tilt = -1.5f,
    )
    AssistantMood.Excited -> FusionBase.copy(
        eyeOpen = 1.22f,
        eyeWidth = 1.2f,
        eyeHeight = 1.18f, // big round rings
        eyeGap = 1.32f, // farthest
        eyeStyle = 0.15f,
        mouthCurve = 0.95f,
        mouthOpen = 0.38f,
        mouthVisible = 1f,
        blush = 0.38f,
        faceGlow = 0.9f,
        tilt = -3.5f,
        blinkSpeed = 1.3f,
    )
    AssistantMood.Sad -> FusionBase.copy(
        eyeOpen = 0.78f,
        eyeWidth = 1.2f,
        eyeHeight = 0.82f,
        eyeGap = 0.96f, // slightly closer than idle, not cramped
        lookY = 0.16f,
        mouthCurve = -0.75f,
        mouthOpen = 0.04f,
        mouthVisible = 0.85f,
        faceGlow = 0.38f,
        tilt = 4f,
        blinkSpeed = 0.65f,
    )
    AssistantMood.Bored -> FusionBase.copy(
        eyeOpen = 0.88f,
        eyeWidth = 1.14f,
        eyeHeight = 0.86f, // slightly half-lidded, not flat slits
        eyeGap = 1.1f,
        lookX = 0.28f,
        lookY = 0.05f,
        eyeStyle = -0.06f,
        faceGlow = 0.4f,
        tilt = 2f,
        blinkSpeed = 0.55f,
    )
    AssistantMood.Drowsy -> FusionBase.copy(
        eyeOpen = 0.72f,
        eyeWidth = 1.14f,
        eyeHeight = 0.7f, // gently heavy lids — no dash swap
        eyeGap = 0.94f,
        lookY = 0.08f,
        eyeStyle = -0.12f,
        faceGlow = 0.4f,
        tilt = 1.5f,
        blinkSpeed = 0.55f,
    )
    AssistantMood.Tired -> FusionBase.copy(
        eyeOpen = 0.78f,
        eyeWidth = 1.12f,
        eyeHeight = 0.76f,
        eyeGap = 0.96f,
        lookY = 0.08f,
        eyeStyle = -0.08f,
        faceGlow = 0.36f,
        tilt = 1.8f,
        blinkSpeed = 0.5f,
    )
}

/**
 * Eye glyph mode for Fusion-family faces.
 */
enum class FusionEyeMode {
    /** Original Fusion oval purple glow rings. */
    OvalGlow,
    /** Immersive-glow purple capsule rings. */
    ImmersiveGlow,
    /** Classic Immersive pale capsule rings (black-face glyphs). */
    Immersive,
}

/**
 * Fusion persona — EPORO shell + glow-ring eyes with Immersive-style expression morphs
 * (shape, spacing, selective mouth, blush, gaze, gestures, lip-sync).
 *
 * @param eyeMode [FusionEyeMode.OvalGlow] (default), [FusionEyeMode.ImmersiveGlow],
 *   or [FusionEyeMode.Immersive] pale Immersive capsules.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
fun FusionAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = EporoGlow,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    shellColor: Color = EporoShell,
    visorColor: Color = EporoVisor,
    glowColor: Color = EporoGlow,
    eyeMode: FusionEyeMode = FusionEyeMode.OvalGlow,
    /**
     * Optional Material glyph drawn as a visor HUD (Weather sink).
     * Crossfades with the eyes via [visorDisplayAlpha] (0 = eyes, 1 = glyph).
     */
    visorDisplayGlyph: ImageVector? = null,
    visorDisplayAlpha: Float = 0f,
    visorDisplayTint: Color? = null,
) {
    val visorPainter = visorDisplayGlyph?.let { rememberVectorPainter(it) }
    val target = mood.toFusionEyePose()
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
    val currentMood by rememberUpdatedState(mood)
    val currentTarget by rememberUpdatedState(target)
    val currentGazeX by rememberUpdatedState(gazeX)

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
                    tilt.animateTo(currentTarget.tilt + 10f, tween(120))
                    tilt.animateTo(currentTarget.tilt - 4f, tween(120))
                }
                tilt.animateTo(currentTarget.tilt, PoseSpring)
            }
            FaceGesture.Shake -> {
                repeat(2) {
                    lookX.animateTo(0.55f, tween(100))
                    lookX.animateTo(-0.55f, tween(100))
                }
                lookX.animateTo(currentGazeX ?: currentTarget.lookX, PoseSpring)
            }
        }
    }

    // Blink stays keyed on Unit so dialogue mood hops don't reset the timer.
    LaunchedEffect(Unit) {
        delay(Random.nextLong(1_200, 2_400))
        while (isActive) {
            val speed = currentTarget.blinkSpeed.coerceIn(0.25f, 1.6f)
            val gapMs = when (currentMood) {
                AssistantMood.Drowsy, AssistantMood.Tired -> Random.nextLong(2_200, 4_000)
                AssistantMood.Excited, AssistantMood.Listening -> Random.nextLong(2_400, 4_200)
                else -> Random.nextLong(2_800, 5_200)
            }
            val closeTo = when (currentMood) {
                AssistantMood.Drowsy -> 0.06f
                AssistantMood.Tired -> 0.08f
                else -> 0.05f
            }
            blink.snapTo(1f)
            if (currentTarget.eyeStyle > 0.55f) {
                // Happy arcs don't blink the same way — skip to next gap.
                delay((gapMs / speed).toLong().coerceAtLeast(800L))
                continue
            }
            blink.animateTo(closeTo, tween((90 / speed).toInt().coerceAtLeast(40)))
            delay((50 / speed).toLong().coerceAtLeast(20L))
            blink.animateTo(1f, tween((140 / speed).toInt().coerceAtLeast(60)))
            if (currentMood == AssistantMood.Tired || currentMood == AssistantMood.Drowsy) {
                delay(120)
                blink.animateTo(closeTo * 1.4f, tween(80))
                delay(40)
                blink.animateTo(1f, tween(160))
            } else if (Random.nextFloat() < 0.25f) {
                delay(100)
                blink.animateTo(closeTo, tween(70))
                delay(35)
                blink.animateTo(1f, tween(130))
            }
            delay((gapMs / speed).toLong().coerceAtLeast(800L))
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

    val life = rememberInfiniteTransition(label = "fusion_life")
    val lifePhase by life.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fusion_phase",
    )
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
        label = "fusion_glow",
    )
    val shellBreath by life.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fusion_breath",
    )
    val idleBob by life.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fusion_bob",
    )
    val idleSway by life.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fusion_sway",
    )

    val blinkOpen = blink.value.coerceIn(0.05f, 1.2f)
    val eyeTint = when (eyeMode) {
        FusionEyeMode.Immersive -> eyeFillForContrast(highContrast)
        FusionEyeMode.ImmersiveGlow -> if (highContrast) eyeFillForContrast(true) else glowColor
        FusionEyeMode.OvalGlow -> if (highContrast) eyeFillForContrast(true) else glowColor
    }
    val mouthTint = eyeFillForContrast(highContrast)

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
        val cy = h * 0.5f
        val side = minOf(w, h)
        val glow = faceGlow.value.coerceIn(0f, 1.2f)
        // Fit EPORO shell into Immersive eyes bounds (same height/size).
        val match = immersiveMatchedShellBounds(w, h)
        val sx = match.width / w
        val sy = match.height / h

        // Soft brand halo behind the head — Immersive presence cue.
        val haloA = auraAlphaForContrast(highContrast, 0.07f) * glow
        val haloR = side * (0.55f + 0.04f * sin(lifePhase * 0.5f).toFloat())
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to brandGlow.copy(alpha = haloA * 0.22f),
                    0.55f to brandGlow.copy(alpha = haloA * 0.06f),
                    1.0f to Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = haloR,
            ),
            radius = haloR,
            center = Offset(cx, cy),
        )

        val liveTilt = tilt.value + 0.35f * sin(lifePhase * 0.28f).toFloat()
        translate(left = match.left, top = match.top) {
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                rotate(degrees = liveTilt, pivot = Offset(cx, h * 0.48f)) {
                    drawFusionHead(shellMorph = shellMorph, shellColor = shellColor, glow = glow)
                    drawFusionVisor(visorColor)

                    val open = (eyeOpen.value * blinkOpen).coerceIn(0.05f, 1.2f)
                    // Wider dynamic spacing — Sad ~0.72 closer, Excited ~1.32 farther.
                    val gap = 0.152f * eyeGap.value.coerceIn(0.65f, 1.45f)
                    val eyeY = h * (0.48f + lookY.value * 0.05f)
                    val gaze = lookX.value * side * 0.018f
                    val pulse = (0.88f + 0.12f * glowPhase).coerceIn(0.82f, 1f)
                    val left = Offset(w * (0.50f - gap) + gaze, eyeY)
                    val right = Offset(w * (0.50f + gap) + gaze, eyeY)
                    val style = eyeStyle.value
                    val glyphA = visorDisplayAlpha.coerceIn(0f, 1f)
                    // Hard swap: once the glyph appears, eyes are gone (no ring behind icons).
                    val eyeReveal = if (glyphA > 0.08f) 0f else 1f

                    if (blush.value > 0.04f) {
                        val blushA = 0.22f * blush.value
                        val bx = w * gap * 0.9f
                        drawCircle(
                            Color(0xFFFF9BB0).copy(alpha = blushA),
                            side * 0.055f,
                            Offset(cx - bx, h * 0.62f),
                        )
                        drawCircle(
                            Color(0xFFFF9BB0).copy(alpha = blushA),
                            side * 0.055f,
                            Offset(cx + bx, h * 0.62f),
                        )
                    }

                    if (eyeReveal > 0.02f || (visorPainter != null && glyphA > 0.02f)) {
                        val faceR = side * 0.42f * pulse
                        val barW = faceR * 0.11f * eyeWidth.value.coerceIn(0.8f, 1.35f)
                        val barH = (faceR * 0.22f * eyeHeight.value.coerceAtMost(1.05f) * open)
                            .coerceAtMost(faceR * 0.26f)
                        val capsuleStyle = style.coerceIn(-0.2f, 0.25f)

                        if (eyeReveal > 0.02f) {
                            val fadedEye = eyeTint.copy(alpha = eyeTint.alpha * eyeReveal)
                            if (eyeMode == FusionEyeMode.ImmersiveGlow || eyeMode == FusionEyeMode.Immersive) {
                                val glowRing = eyeMode == FusionEyeMode.ImmersiveGlow
                                drawNomiGlyphEye(left, barW, barH, capsuleStyle, fadedEye, glowRing = glowRing)
                                drawNomiGlyphEye(right, barW, barH, capsuleStyle, fadedEye, glowRing = glowRing)
                            } else {
                                val baseR = side * 0.082f * pulse
                                val rx = baseR * eyeWidth.value.coerceIn(0.75f, 1.45f)
                                val ry = (baseR * eyeHeight.value.coerceIn(0.35f, 1.3f) * open)
                                    .coerceAtLeast(baseR * 0.06f)
                                drawFusionEye(
                                    center = left,
                                    radiusX = rx,
                                    radiusY = ry,
                                    glow = fadedEye,
                                    open = blinkOpen,
                                    style = style,
                                )
                                drawFusionEye(
                                    center = right,
                                    radiusX = rx,
                                    radiusY = ry,
                                    glow = fadedEye,
                                    open = blinkOpen,
                                    style = style,
                                )
                            }
                        }

                        // Replace both eyes with weather glyphs — same capsule size + morph.
                        if (visorPainter != null && glyphA > 0.02f) {
                            val tint = visorDisplayTint ?: eyeTint
                            val glyphW: Float
                            val glyphH: Float
                            if (eyeMode == FusionEyeMode.ImmersiveGlow || eyeMode == FusionEyeMode.Immersive) {
                                glyphW = barW
                                glyphH = barH
                            } else {
                                val baseR = side * 0.082f * pulse
                                glyphW = baseR * eyeWidth.value.coerceIn(0.75f, 1.45f)
                                glyphH = (baseR * eyeHeight.value.coerceIn(0.35f, 1.3f) * open)
                                    .coerceAtLeast(baseR * 0.06f)
                            }
                            drawWeatherEyeReplacement(
                                painter = visorPainter,
                                center = left,
                                halfW = glyphW,
                                halfH = glyphH,
                                style = capsuleStyle,
                                alpha = glyphA,
                                tint = tint,
                            )
                            drawWeatherEyeReplacement(
                                painter = visorPainter,
                                center = right,
                                halfW = glyphW,
                                halfH = glyphH,
                                style = capsuleStyle,
                                alpha = glyphA,
                                tint = tint,
                            )
                        }
                    }

                    val speaking = mouthAmplitude != null ||
                        mood == AssistantMood.Speaking ||
                        mood == AssistantMood.Excited
                    // Mouth only when the pose asks for it (happy / sad / speaking / …).
                    if (mouthVisible.value > 0.2f || (mouthAmplitude != null && mouthAmplitude > 0.05f)) {
                        drawFusionMouth(
                            center = Offset(cx, h * 0.66f),
                            faceR = side * 0.42f,
                            curve = mouthCurve.value,
                            open = mouthOpen.value,
                            visible = maxOf(
                                mouthVisible.value,
                                if (mouthAmplitude != null) 0.9f else 0f,
                            ),
                            color = mouthTint,
                            speaking = speaking,
                            life = lifePhase,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Weather icon drawn as a full eye replacement — same footprint as Immersive
 * eyes ([halfW]/[halfH]), morphing with blink/open and eye style, with no
 * capsule/ring chrome so it does not read as “inside” the eye.
 */
private fun DrawScope.drawWeatherEyeReplacement(
    painter: Painter,
    center: Offset,
    halfW: Float,
    halfH: Float,
    style: Float,
    alpha: Float,
    tint: Color,
) {
    val w = halfW.coerceAtLeast(1.5f)
    val h = when {
        style < -0.25f -> {
            val flatten = (-style).coerceIn(0.25f, 1f)
            (halfH * (1f - 0.55f * flatten)).coerceAtLeast(w * 0.55f)
        }
        style > 0.28f -> halfH * (1f + 0.2f * style.coerceAtMost(1f))
        else -> halfH.coerceAtLeast(w * 0.55f)
    }
    // Match Immersive eye outer bounds without any capsule/ring chrome.
    val side = max(w * 2f, h * 2f).coerceAtLeast(1f)
    translate(
        left = center.x - side * 0.5f,
        top = center.y - side * 0.5f,
    ) {
        with(painter) {
            draw(
                size = Size(side, side),
                alpha = alpha,
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

private fun DrawScope.drawFusionHead(
    shellMorph: ExpressiveShellMorphState,
    shellColor: Color,
    glow: Float,
) {
    val w = size.width
    val h = size.height
    val pad = minOf(w, h) * 0.018f
    val bounds = Rect(left = pad, top = pad, right = w - pad, bottom = h - pad)
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
    drawExpressiveFaceShell(
        morphState = shellMorph,
        bounds = bounds,
        color = EporoShellRim.copy(alpha = (0.45f + 0.2f * glow).coerceIn(0.35f, 0.7f)),
        style = Stroke(width = 0.022f, cap = StrokeCap.Round),
    )
}

private fun fusionVisorPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.18f, h * 0.34f)
    cubicTo(w * 0.26f, h * 0.22f, w * 0.74f, h * 0.22f, w * 0.82f, h * 0.34f)
    cubicTo(w * 0.88f, h * 0.44f, w * 0.86f, h * 0.62f, w * 0.76f, h * 0.68f)
    cubicTo(w * 0.66f, h * 0.73f, w * 0.58f, h * 0.76f, w * 0.50f, h * 0.76f)
    cubicTo(w * 0.42f, h * 0.76f, w * 0.34f, h * 0.73f, w * 0.24f, h * 0.68f)
    cubicTo(w * 0.14f, h * 0.62f, w * 0.12f, h * 0.44f, w * 0.18f, h * 0.34f)
    close()
}

private fun DrawScope.drawFusionVisor(visorColor: Color) {
    val w = size.width
    val h = size.height
    val p = fusionVisorPath(w, h)
    drawPath(path = p, color = visorColor)
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
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(w * 0.44f, h * 0.28f),
        size = Size(w * 0.12f, h * 0.024f),
        cornerRadius = CornerRadius(h * 0.02f, h * 0.02f),
    )
}

/** EPORO glow eyes — morph ring / arc / dash shapes like Immersive. */
private fun DrawScope.drawFusionEye(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    glow: Color,
    open: Float,
    style: Float,
) {
    val rx = radiusX.coerceAtLeast(1f)
    val ry = radiusY.coerceAtLeast(rx * 0.06f)
    when {
        style > 0.35f -> {
            // Happy ^ arcs with EPORO bloom.
            val lift = 0.55f + 0.55f * style.coerceIn(0.35f, 1f)
            val path = Path().apply {
                moveTo(center.x - rx * 1.75f, center.y + ry * 0.35f)
                quadraticTo(
                    center.x,
                    center.y - ry * lift,
                    center.x + rx * 1.75f,
                    center.y + ry * 0.35f,
                )
            }
            drawIntoCanvas { canvas ->
                val fw = Paint().asFrameworkPaint()
                fw.isAntiAlias = true
                fw.color = glow.copy(alpha = 0.4f).toArgb()
                fw.maskFilter = BlurMaskFilter(rx * 0.85f, BlurMaskFilter.Blur.NORMAL)
                fw.strokeWidth = rx * 0.55f
                fw.strokeCap = android.graphics.Paint.Cap.ROUND
                fw.style = android.graphics.Paint.Style.STROKE
                canvas.nativeCanvas.drawPath(
                    android.graphics.Path().apply {
                        moveTo(center.x - rx * 1.75f, center.y + ry * 0.35f)
                        quadTo(
                            center.x,
                            center.y - ry * lift,
                            center.x + rx * 1.75f,
                            center.y + ry * 0.35f,
                        )
                    },
                    fw,
                )
            }
            drawPath(
                path,
                glow.copy(alpha = 0.98f),
                style = Stroke(width = rx * 0.48f, cap = StrokeCap.Round),
            )
        }
        style < -0.25f -> {
            // Sleepy glow dashes — flattened rings.
            val flatten = (-style).coerceIn(0.25f, 1f)
            val dashW = rx * (1.35f + 0.45f * flatten)
            val dashH = (ry * (1f - 0.55f * flatten) * open.coerceIn(0.15f, 1f))
                .coerceAtLeast(rx * 0.12f)
            drawIntoCanvas { canvas ->
                val paint = Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = glow.copy(alpha = 0.4f).toArgb()
                    maskFilter = BlurMaskFilter(dashW * 0.55f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.nativeCanvas.drawOval(
                    center.x - dashW * 1.35f,
                    center.y - dashH * 1.35f,
                    center.x + dashW * 1.35f,
                    center.y + dashH * 1.35f,
                    paint,
                )
            }
            drawRoundRect(
                color = glow.copy(alpha = 0.96f),
                topLeft = Offset(center.x - dashW, center.y - dashH),
                size = Size(dashW * 2f, dashH * 2f),
                cornerRadius = CornerRadius(dashH, dashH),
            )
        }
        else -> {
            // Morphing glow rings — width/height express the mood.
            scale(scaleX = 1f, scaleY = open.coerceIn(0.05f, 1.2f), pivot = center) {
                val bloom = maxOf(rx, ry)
                drawIntoCanvas { canvas ->
                    val paint = Paint().asFrameworkPaint().apply {
                        isAntiAlias = true
                        color = glow.copy(alpha = 0.45f * open.coerceIn(0.2f, 1f)).toArgb()
                        maskFilter = BlurMaskFilter(bloom * 0.9f, BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawOval(
                        center.x - rx * 1.55f,
                        center.y - ry * 1.55f,
                        center.x + rx * 1.55f,
                        center.y + ry * 1.55f,
                        paint,
                    )
                }
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.35f), Color.Transparent),
                        center = center,
                        radius = bloom * 1.7f,
                    ),
                    topLeft = Offset(center.x - rx * 1.7f, center.y - ry * 1.7f),
                    size = Size(rx * 3.4f, ry * 3.4f),
                )
                val strokeW = minOf(rx, ry) * 0.38f
                drawOval(
                    color = glow.copy(alpha = 0.98f),
                    topLeft = Offset(center.x - rx, center.y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
                drawOval(
                    color = Color.Black,
                    topLeft = Offset(center.x - rx * 0.55f, center.y - ry * 0.55f),
                    size = Size(rx * 1.1f, ry * 1.1f),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = minOf(rx, ry) * 0.12f,
                    center = Offset(center.x, center.y + ry * 0.7f),
                )
            }
        }
    }
}

private fun DrawScope.drawFusionMouth(
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

/**
 * Fusion shell + Immersive-glow capsule eyes (EPORO purple bloom).
 * Mood morphs, mouth, blush, and gestures match [FusionAssistantFace].
 */
@Composable
fun FusionGlowAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = EporoGlow,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    shellColor: Color = EporoShell,
    visorColor: Color = EporoVisor,
    glowColor: Color = EporoGlow,
) {
    FusionAssistantFace(
        mood = mood,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        shellColor = shellColor,
        visorColor = visorColor,
        glowColor = glowColor,
        eyeMode = FusionEyeMode.ImmersiveGlow,
    )
}

/**
 * Fusion shell + classic Immersive pale capsule eyes (black-face glyphs).
 * Mood morphs, mouth, blush, and gestures match [FusionAssistantFace].
 */
@Composable
fun FusionEyesAssistantFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = EporoGlow,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    shellColor: Color = EporoShell,
    visorColor: Color = EporoVisor,
    glowColor: Color = EporoGlow,
    visorDisplayGlyph: ImageVector? = null,
    visorDisplayAlpha: Float = 0f,
    visorDisplayTint: Color? = null,
) {
    FusionAssistantFace(
        mood = mood,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        shellColor = shellColor,
        visorColor = visorColor,
        glowColor = glowColor,
        eyeMode = FusionEyeMode.Immersive,
        visorDisplayGlyph = visorDisplayGlyph,
        visorDisplayAlpha = visorDisplayAlpha,
        visorDisplayTint = visorDisplayTint,
    )
}

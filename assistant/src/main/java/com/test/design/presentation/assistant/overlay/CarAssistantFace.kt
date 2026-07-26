package com.test.design.presentation.assistant.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Fixed capsule geometry from the pixel blueprint (420 × 180). */
internal object CarAssistantGeometry {
    val CapsuleWidth = 420.dp
    val CapsuleHeight = 180.dp
    val CapsuleCorner = 90.dp
    val BottomInset = 32.dp
    val BugSize = 64.dp

    val CenterX = 210.dp
    val EyesBaselineY = 72.dp
    val EyeHalfSpacing = 92.5.dp
    val EyeCorner = 25.dp
    val MouthBaselineY = 117.dp
    val MouthStroke = 8.dp
}

/** Sunlight-safe high-contrast palette. */
internal object CarAssistantColors {
    /** Blackish glass capsule — mostly solid so eyes/mouth stay crisp. */
    val CapsuleBackground = Color(0xE6121418)
    val Idle = Color(0xFFE0E0E0)
    val Listening = Color(0xFF4DADFF)
    val Thinking = Color(0xFFB388FF)
    val Error = Color(0xFFFF6B6B)
    val Speaking = Color(0xFFE0E0E0)
}

/**
 * Pure Canvas face — coordinates locked to the 420×180 blueprint.
 *
 * @param audioAmplitude 0..1 speech energy; SPEAKING modulates jaw/eyes with it.
 */
@Composable
fun CarAssistantFace(
    state: AssistantState,
    modifier: Modifier = Modifier,
    audioAmplitude: Float = 0f,
) {
    val density = LocalDensity.current
    val amp = audioAmplitude.coerceIn(0f, 1f)

    val transition = updateTransition(targetState = state, label = "assistant_face")

    val eyeH by transition.animateDp(label = "eye_h") { s ->
        when (s) {
            AssistantState.IDLE -> 32.dp
            AssistantState.LISTENING -> 48.dp
            AssistantState.THINKING -> 8.dp
            AssistantState.SPEAKING -> (40f + amp * 8f).dp
            AssistantState.ERROR -> 20.dp
        }
    }
    val eyeWLeft by transition.animateDp(label = "eye_w_l") { s ->
        when (s) {
            AssistantState.ERROR -> 35.dp
            else -> 50.dp
        }
    }
    val eyeWRight by transition.animateDp(label = "eye_w_r") { 50.dp }
    val mouthSpan by transition.animateDp(label = "mouth_span") { s ->
        when (s) {
            AssistantState.THINKING -> 52.5.dp
            AssistantState.SPEAKING -> (84f + amp * 42f).dp
            else -> 105.dp
        }
    }
    val mouthControlY by transition.animateDp(label = "mouth_ctrl_y") { s ->
        when (s) {
            AssistantState.IDLE -> 122.dp
            AssistantState.LISTENING -> 117.dp
            AssistantState.THINKING -> 117.dp
            AssistantState.SPEAKING -> (117f + 27f * (amp + 0.3f)).dp
            AssistantState.ERROR -> 105.dp
        }
    }

    val glyphColor = when (state) {
        AssistantState.IDLE -> CarAssistantColors.Idle
        AssistantState.LISTENING -> CarAssistantColors.Listening
        AssistantState.THINKING -> CarAssistantColors.Thinking
        AssistantState.SPEAKING -> CarAssistantColors.Speaking
        AssistantState.ERROR -> CarAssistantColors.Error
    }

    val infinite = rememberInfiniteTransition(label = "think_drift")
    val driftPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift",
    )
    val thinkDriftDp =
        if (state == AssistantState.THINKING) (sin(driftPhase.toDouble()).toFloat() * 15f).dp else 0.dp

    val blinkScale = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state != AssistantState.IDLE) {
            blinkScale.snapTo(1f)
            return@LaunchedEffect
        }
        while (isActive) {
            delay(Random.nextLong(3000, 6000))
            blinkScale.snapTo(4f / 32f)
            delay(120)
            blinkScale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f))
        }
    }

    val eyeHeightLeft = when (state) {
        AssistantState.IDLE -> eyeH * blinkScale.value
        else -> eyeH
    }
    val eyeHeightRight = eyeHeightLeft

    Box(
        modifier = modifier
            .size(CarAssistantGeometry.CapsuleWidth, CarAssistantGeometry.CapsuleHeight)
            .background(
                CarAssistantColors.CapsuleBackground,
                RoundedCornerShape(CarAssistantGeometry.CapsuleCorner),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            fun Dp.px(): Float = with(density) { toPx() }

            val centerX = CarAssistantGeometry.CenterX.px()
            val eyesY = CarAssistantGeometry.EyesBaselineY.px()
            val halfGap = CarAssistantGeometry.EyeHalfSpacing.px()
            val drift = thinkDriftDp.px()
            val corner = CarAssistantGeometry.EyeCorner.px()

            val leftW = eyeWLeft.px()
            val leftH = eyeHeightLeft.px().coerceAtLeast(1f)
            val leftCx = centerX - halfGap + drift +
                if (state == AssistantState.ERROR) 8.dp.px() else 0f
            val leftTopLeft = Offset(leftCx - leftW * 0.5f, eyesY - leftH * 0.5f)

            val rightW = eyeWRight.px()
            val rightH = eyeHeightRight.px().coerceAtLeast(1f)
            val rightCx = centerX + halfGap + drift
            val rightTopLeft = Offset(rightCx - rightW * 0.5f, eyesY - rightH * 0.5f)

            drawRoundRect(
                color = glyphColor,
                topLeft = leftTopLeft,
                size = Size(leftW, leftH),
                cornerRadius = CornerRadius(corner, corner),
            )
            drawRoundRect(
                color = glyphColor,
                topLeft = rightTopLeft,
                size = Size(rightW, rightH),
                cornerRadius = CornerRadius(corner, corner),
            )

            val mouthY = CarAssistantGeometry.MouthBaselineY.px()
            val span = mouthSpan.px()
            val mouthStartX = centerX - span * 0.5f
            val mouthEndX = centerX + span * 0.5f
            val ctrlY = mouthControlY.px()
            val path = Path().apply {
                moveTo(mouthStartX, mouthY)
                quadraticTo(centerX, ctrlY, mouthEndX, mouthY)
            }
            drawPath(
                path = path,
                color = glyphColor,
                style = Stroke(
                    width = CarAssistantGeometry.MouthStroke.px(),
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
}

/** Passive 64dp corner bug — compact IDLE glyph. */
@Composable
fun CarAssistantBug(
    state: AssistantState,
    modifier: Modifier = Modifier,
) {
    val color = when (state) {
        AssistantState.LISTENING -> CarAssistantColors.Listening
        AssistantState.THINKING -> CarAssistantColors.Thinking
        AssistantState.ERROR -> CarAssistantColors.Error
        else -> CarAssistantColors.Idle
    }
    Box(
        modifier = modifier
            .size(CarAssistantGeometry.BugSize)
            .background(CarAssistantColors.CapsuleBackground, RoundedCornerShape(50)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.42f
            val eyeW = size.width * 0.16f
            val eyeH = size.height * 0.22f
            val gap = size.width * 0.14f
            val r = eyeW * 0.5f
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - gap - eyeW, cy - eyeH * 0.5f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(r, r),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(cx + gap, cy - eyeH * 0.5f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

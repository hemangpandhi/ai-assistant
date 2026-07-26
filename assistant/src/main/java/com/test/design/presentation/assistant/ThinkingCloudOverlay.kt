package com.test.design.presentation.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

private val ThinkCloudFill = Color(0xFFE8ECFF)
private val ThinkCloudStroke = Color(0xFFF5F7FF)
private val ThinkCloudDot = Color(0xFFB39DDB)

/**
 * Thinking cloud that animates in/out at the top-right of a face (or stage).
 */
@Composable
fun ThinkingCloudOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val life = rememberInfiniteTransition(label = "think_cloud_life")
    val phase by life.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "think_cloud_phase",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(280)) +
            scaleIn(
                initialScale = 0.55f,
                animationSpec = spring(
                    dampingRatio = 0.68f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) +
            slideInHorizontally(
                initialOffsetX = { it / 3 },
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        exit = fadeOut(tween(200)) +
            scaleOut(targetScale = 0.7f, animationSpec = tween(200)) +
            slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ),
        modifier = modifier,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            val bob = sin(phase.toDouble()).toFloat() * side * 0.04f
            val anchor = Offset(size.width * 0.58f, size.height * 0.42f + bob)
            drawThinkingThoughtCloud(
                anchor = anchor,
                cloudSize = side * 0.72f,
                life = phase,
            )
        }
    }
}

/**
 * Wraps any face content with a thinking cloud at the top-right when [mood] is Thinking.
 */
@Composable
fun FaceWithThinkingCloud(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    cloudFraction: Float = 0.42f,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        content()
        ThinkingCloudOverlay(
            visible = mood == AssistantMood.Thinking,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-4).dp)
                .fillMaxSize(cloudFraction),
        )
    }
}

private fun DrawScope.drawThinkingThoughtCloud(
    anchor: Offset,
    cloudSize: Float,
    life: Float,
) {
    val fill = ThinkCloudFill.copy(alpha = 0.94f)
    val stroke = ThinkCloudStroke.copy(alpha = 0.95f)
    val s = cloudSize

    val c1 = Offset(anchor.x, anchor.y)
    val c2 = Offset(anchor.x - s * 0.28f, anchor.y + s * 0.06f)
    val c3 = Offset(anchor.x + s * 0.3f, anchor.y + s * 0.04f)
    val c4 = Offset(anchor.x + s * 0.05f, anchor.y - s * 0.22f)
    drawCircle(fill, s * 0.32f, c1)
    drawCircle(fill, s * 0.26f, c2)
    drawCircle(fill, s * 0.28f, c3)
    drawCircle(fill, s * 0.24f, c4)
    drawCircle(stroke, s * 0.32f, c1, style = Stroke(1.5f))
    drawCircle(stroke, s * 0.26f, c2, style = Stroke(1.5f))
    drawCircle(stroke, s * 0.28f, c3, style = Stroke(1.5f))
    drawCircle(stroke, s * 0.24f, c4, style = Stroke(1.5f))

    drawCircle(fill, s * 0.08f, Offset(anchor.x - s * 0.42f, anchor.y + s * 0.32f))
    drawCircle(fill, s * 0.05f, Offset(anchor.x - s * 0.55f, anchor.y + s * 0.48f))

    val dotY = anchor.y + s * 0.02f
    for (i in 0..2) {
        val bounce = sin(life * 2f + i * 0.9f).toFloat() * s * 0.06f
        drawCircle(
            color = ThinkCloudDot.copy(alpha = 0.9f),
            radius = s * 0.055f,
            center = Offset(anchor.x - s * 0.16f + i * s * 0.16f, dotY + bounce),
        )
    }
}

package com.test.design.presentation.assistant

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Listening corner bubble — same [ImmersiveEyesFace] as fullscreen, elevated glass chip.
 * Non-blocking until the session morphs to immersive.
 *
 * Sits above the floating dock so it is not covered when system bars are visible.
 * Bottom inset comes from [LocalAssistantChromeBottomSpace] (host-provided).
 */
@Composable
fun AssistantCornerBubble(
    mood: AssistantMood,
    prompt: String,
    modifier: Modifier = Modifier,
    brandGlow: Color = AssistantTokens.Accent,
    onClick: (() -> Unit)? = null,
    onBoundsInRoot: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null,
) {
    val chromeBottom = LocalAssistantChromeBottomSpace.current
    val pulse = rememberInfiniteTransition(label = "corner_bubble_pulse")
    val glowAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corner_glow",
    )
    val lift by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corner_lift",
    )

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        CornerBubbleGlass(
            brandGlow = brandGlow,
            glowAlpha = glowAlpha,
            modifier = Modifier
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 20.dp,
                    bottom = 20.dp + chromeBottom,
                )
                .widthIn(min = 300.dp, max = 360.dp)
                .heightIn(min = 96.dp)
                .graphicsLayer {
                    scaleX = lift
                    scaleY = lift
                }
                .then(
                    if (onBoundsInRoot != null) {
                        Modifier.onGloballyPositioned { coords ->
                            val b = coords.boundsInRoot()
                            onBoundsInRoot(
                                b.left.roundToInt(),
                                b.top.roundToInt(),
                                b.right.roundToInt(),
                                b.bottom.roundToInt(),
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Same persona as fullscreen immersive stage.
                ImmersiveEyesFace(
                    mood = mood,
                    brandGlow = brandGlow,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = prompt.ifBlank { "Listening…" },
                    color = AssistantTokens.OnSurface,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun CornerBubbleGlass(
    brandGlow: Color,
    glowAlpha: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = brandGlow.copy(alpha = 0.55f),
                spotColor = brandGlow.copy(alpha = 0.7f),
            )
            .clip(shape)
            .background(Color(0xF214161C), shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        brandGlow.copy(alpha = 0.22f * glowAlpha),
                        Color.Transparent,
                        brandGlow.copy(alpha = 0.10f),
                    ),
                ),
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        brandGlow.copy(alpha = 0.75f * glowAlpha + 0.25f),
                        Color.White.copy(alpha = 0.2f),
                    ),
                ),
                shape = shape,
            ),
        content = { content() },
    )
}

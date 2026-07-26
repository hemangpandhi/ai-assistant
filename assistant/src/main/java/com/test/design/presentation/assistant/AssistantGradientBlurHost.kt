package com.test.design.presentation.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Max blur under the immersive assistant.
 * Masked to the center ~40% width and bottom of the host (30–40–30).
 */
val AssistantBackdropBlur = 36.dp

/**
 * Horizontal mask: clear side gutters (30%) → soft shoulders → full blur in center 40%.
 * Matches [ImmersiveBackdrop] width falloff.
 */
internal val AssistantCenterBandHorizontalStops: Array<Pair<Float, Color>> = arrayOf(
    0.00f to Color.Transparent,
    0.22f to Color.Transparent,
    0.30f to Color.White.copy(alpha = 0.25f),
    0.36f to Color.White.copy(alpha = 0.85f),
    0.42f to Color.White,
    0.50f to Color.White,
    0.58f to Color.White,
    0.64f to Color.White.copy(alpha = 0.85f),
    0.70f to Color.White.copy(alpha = 0.25f),
    0.78f to Color.Transparent,
    1.00f to Color.Transparent,
)

/** Vertical mask: sharp top → full blur toward the bottom stage. */
internal val AssistantCenterBandVerticalStops: Array<Pair<Float, Color>> = arrayOf(
    0.00f to Color.Transparent,
    0.40f to Color.Transparent,
    0.62f to Color.White.copy(alpha = 0.35f),
    0.82f to Color.White.copy(alpha = 0.85f),
    1.00f to Color.White,
)

/**
 * Host content stays sharp at the sides and top; a second pass is blurred and
 * masked so strength ramps into the center 40% band and toward the bottom.
 */
@Composable
fun AssistantGradientBlurHost(
    blurred: Boolean,
    modifier: Modifier = Modifier,
    maxBlur: Dp = AssistantBackdropBlur,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (blurred) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Keep bottom stage…
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = AssistantCenterBandVerticalStops,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                        // …only inside the center ~40% width (soft 30–40–30).
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = AssistantCenterBandHorizontalStops,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .blur(
                        radius = maxBlur,
                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                    ),
            ) {
                content()
            }
        }
    }
}

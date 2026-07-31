package com.assistant.ui.assistant.ui.immersive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft semicircle stage under the immersive face + transcript.
 *
 * Bottom-center pivot (same fashion as the SemiCircle face shell): core is
 * fully opaque (100%) in the outer-border blue, then dissolves to 0% along the
 * semicircle arc — no rectangle, no stroke, no hard silhouette.
 */
@Composable
fun FaceStageDock(
    brandGlow: Color,
    width: Dp,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Match ImmersiveBorderGlow / panel blue — ignore incoming alpha, own the fade.
    val blue = brandGlow.copy(alpha = 1f)

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
            .drawBehind {
                val cx = size.width * 0.5f
                // Sit the pivot on the bottom edge → only the upper semicircle paints.
                val cy = size.height
                // Half-width radius: 0% lands exactly at left, right, and the top arc.
                val radius = size.width * 0.5f

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to blue.copy(alpha = 1.00f), // 100%
                            0.25f to blue.copy(alpha = 0.92f),
                            0.50f to blue.copy(alpha = 0.62f),
                            0.72f to blue.copy(alpha = 0.32f),
                            0.88f to blue.copy(alpha = 0.10f),
                            1.00f to blue.copy(alpha = 0.00f), // 0%
                        ),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    center = Offset(cx, cy),
                    radius = radius,
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Keep chrome inside the opaque core of the semicircle.
                .padding(start = 48.dp, top = 36.dp, end = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            content = content,
        )
    }
}

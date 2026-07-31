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
 * fully opaque (100%), then the fill dissolves to 0% along the semicircle arc.
 * Radius is always half the dock width so left / right / top of the arc hit
 * true 0% inside the layout — never clipped into a hard edge.
 */
@Composable
fun FaceStageDock(
    brandGlow: Color,
    width: Dp,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glowTint = brandGlow.copy(alpha = 0.16f)

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
            .drawBehind {
                val cx = size.width * 0.5f
                // Sit the pivot on the bottom edge → only the upper semicircle paints.
                val cy = size.height
                // Half-width radius: 0% lands exactly at left, right, and the top arc.
                // Parent must size [width] ≥ ~2× content height so the face fits inside.
                val radius = size.width * 0.5f

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFF121418), // 100%
                            0.25f to Color(0xFF121418),
                            0.50f to Color(0xC2121418),
                            0.72f to Color(0x73101418),
                            0.88f to Color(0x2A101418),
                            1.00f to Color.Transparent, // 0%
                        ),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    center = Offset(cx, cy),
                    radius = radius,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.45f to glowTint.copy(alpha = 0.08f),
                            0.72f to glowTint,
                            0.90f to glowTint.copy(alpha = 0.05f),
                            1.00f to Color.Transparent,
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

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
 * Local soft glass dock under the immersive face + transcript.
 *
 * Near-solid dark core keeps the persona readable over maps / launcher, then
 * the fill radially dissolves to full transparency — no hard card edge or
 * stroked border (cert-safe: chrome-local only).
 */
@Composable
fun FaceStageDock(
    brandGlow: Color,
    width: Dp,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glowTint = brandGlow.copy(alpha = 0.20f)

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
            .drawBehind {
                val cx = size.width * 0.5f
                val cy = size.height * 0.52f
                val radius = size.maxDimension * 0.72f

                // Dark readable core → soft falloff → clear. No hard silhouette.
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xF2121418),
                            0.28f to Color(0xE0121418),
                            0.48f to Color(0xA00C0E12),
                            0.68f to Color(0x55081014),
                            0.85f to Color(0x18081014),
                            1.0f to Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                )
                // Soft brand bloom that also dies to transparency (no rim stroke).
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.35f to glowTint.copy(alpha = 0.14f),
                            0.62f to glowTint,
                            0.82f to glowTint.copy(alpha = 0.08f),
                            1.0f to Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = radius * 1.08f,
                    ),
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            content = content,
        )
    }
}

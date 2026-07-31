package com.assistant.ui.assistant.ui.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Local glass dock under the immersive face + transcript.
 *
 * Near-solid blackish plate keeps the persona readable over maps / launcher
 * without a full-screen opaque or system blur (cert-safe: chrome-local only).
 * A soft radial veil outside the plate dissolves into the clear stage.
 */
@Composable
fun FaceStageDock(
    brandGlow: Color,
    width: Dp,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(36.dp)
    val edge = brandGlow.copy(alpha = 0.38f)
    val glowTint = brandGlow.copy(alpha = 0.18f)

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
            // Soft veil in the outer box so it bleeds past the inset glass plate.
            .drawBehind {
                val cx = size.width * 0.5f
                val cy = size.height * 0.58f
                val radius = size.maxDimension * 0.82f
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xAA05060A),
                            0.40f to Color(0x55081014),
                            0.70f to glowTint,
                            1.0f to Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AssistantTokens.SurfaceTop,
                            AssistantTokens.Surface,
                            AssistantTokens.SurfaceBottom,
                        ),
                    ),
                    shape,
                )
                .border(1.dp, edge, shape)
                .padding(start = 28.dp, top = 20.dp, end = 28.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            content = content,
        )
    }
}

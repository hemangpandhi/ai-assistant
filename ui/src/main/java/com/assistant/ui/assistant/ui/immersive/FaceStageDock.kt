package com.assistant.ui.assistant.ui.immersive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens

/** Soft panel blue from [ImmersiveBorderGlow] spectrum — not OEM purple. */
private val BorderPanelBlue = Color(0xFF8AB4F8)
private val BorderIceBlue = Color(0xFF6EC8FF)

/**
 * Soft semicircle stage under the immersive face + transcript.
 *
 * Cool border-glow blue (not purple brand accent), dissolving 100%→0% along a
 * semicircle. Opacity breathes with [glowBreath] so the dock stays in sync
 * with the outer rim.
 *
 * Used by classic hybrid (master-style overlay); island capsule chrome uses
 * [IslandCapsuleDock] instead.
 */
@Composable
fun FaceStageDock(
    brandGlow: Color,
    width: Dp,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Anchor on border panel blue; only a whisper of brandGlow so OEM tint
    // never pulls the dock purple.
    val blue = remember(brandGlow) {
        val brand = brandGlow.copy(alpha = 1f)
        lerp(BorderPanelBlue, lerp(BorderIceBlue, brand, 0.20f), 0.18f)
    }
    // Subtle breath: rest soft, inhale a touch brighter — matches rim fade.
    val breathMul = (0.72f + glowBreath.inhale * 0.38f + (glowBreath.fade - 0.62f) * 0.25f)
        .coerceIn(0.65f, 1.15f)

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
            .drawBehind {
                val cx = size.width * 0.5f
                val cy = size.height
                val radius = size.width * 0.5f
                // Softer peak so the dock plate doesn't fight the linear black veil.
                val peak = (0.16f * breathMul).coerceIn(0.10f, 0.22f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to blue.copy(alpha = peak),
                            0.28f to blue.copy(alpha = peak * 0.62f),
                            0.52f to blue.copy(alpha = peak * 0.34f),
                            0.74f to blue.copy(alpha = peak * 0.14f),
                            0.90f to blue.copy(alpha = peak * 0.04f),
                            1.00f to blue.copy(alpha = 0.00f),
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
                .padding(
                    start = 48.dp,
                    top = 36.dp,
                    end = 48.dp,
                    bottom = AssistantOverlayTokens.DockContentPaddingBottom,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            content = content,
        )
    }
}

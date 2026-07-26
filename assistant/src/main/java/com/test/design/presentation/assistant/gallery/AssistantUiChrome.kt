package com.test.design.presentation.assistant.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.test.design.presentation.assistant.AssistantTokens

/** Shared opaque surfaces for every gallery variant. */
internal object AssistantUiChrome {
    /** Solid blackish plates — fully opaque for all gallery styles. */
    val Glass = Color(0xFF121418)
    val GlassLight = Color(0xFF1A1C20)
    val GlassEdge = Color.White.copy(alpha = 0.16f)
    /** Opaque stage fill (no see-through to content behind the gallery). */
    val Scrim = Color(0xFF101014)
    val Accent = AssistantTokens.Accent
    val AccentSoft = AssistantTokens.PanelGlow
    val OnGlass = AssistantTokens.OnSurface
    val OnGlassMuted = AssistantTokens.OnSurfaceVariant
}

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        AssistantUiChrome.GlassLight,
                        AssistantUiChrome.Glass,
                    ),
                ),
                shape,
            )
            .border(1.dp, AssistantUiChrome.GlassEdge, shape),
        content = { content() },
    )
}

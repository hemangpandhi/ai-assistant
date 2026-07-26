package com.test.design.presentation.assistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Production tokens for the assistant panel — restrained Google-like surfaces.
 *
 * Glass stays blackish and mostly solid so the face/glyphs stay readable over
 * busy map / dashboard UI, while a soft stage scrim dims the world behind.
 */
internal object AssistantTokens {
    val PanelWidth = 400.dp
    val PanelCorner = 28.dp
    val PanelInset = 12.dp
    val ContentPadding = 28.dp

    /** Blackish glass plates (~88% opacity). */
    val Surface = Color(0xE0121418)
    val SurfaceTop = Color(0xE61A1C20)
    val SurfaceBottom = Color(0xD90C0E12)
    val Hairline = Color.White.copy(alpha = 0.14f)

    val OnSurface = Color(0xFFF1F3F4)
    val OnSurfaceVariant = Color(0xFF9AA0A6)
    val OnSurfaceMuted = Color(0xFF80868B)

    val Accent = Color(0xFF8AB4F8)
    val PanelGlow = Color(0x668AB4F8)
    val PanelGlowSoft = Color(0x448AB4F8)
    val PanelGlowEdge = Color(0xFF8AB4F8)

    /** Soft blackish stage dim — underlying UI stays visible. */
    val Scrim = Color(0x59101014)

    const val EnterMs = 400
    const val ExitMs = 280
    const val CrossfadeMs = 320
}

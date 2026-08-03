package com.assistant.ui.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Self-contained theme for the assistant module (no dependency on app OemBrand / AppTheme).
 * Host apps may wrap [AssistantTheme] or replace [LocalAssistantThemeWrapper].
 */
private val AssistantDarkColors = darkColorScheme(
    primary = Color(0xFF9A7DFF),
    onPrimary = Color(0xFF1A1030),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE8E8EA),
)

private val AssistantLightColors = lightColorScheme(
    primary = Color(0xFF6B4EFF),
    onPrimary = Color.White,
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF1C1D21),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AssistantDarkColors else AssistantLightColors,
        // Physics-based springs for hero spatial motion (face entrance, etc.).
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * Optional host override — `:app` can point this at Oem [AppTheme] during Application.onCreate.
 */
val LocalAssistantThemeWrapper = staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> {
    { content -> AssistantTheme(content = content) }
}

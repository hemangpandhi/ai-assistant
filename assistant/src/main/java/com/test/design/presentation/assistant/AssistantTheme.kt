package com.test.design.presentation.assistant

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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

@Composable
fun AssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AssistantDarkColors else AssistantLightColors,
        content = content,
    )
}

/**
 * Optional host override — `:app` can point this at Oem [AppTheme] during Application.onCreate.
 */
val LocalAssistantThemeWrapper = staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> {
    { content -> AssistantTheme(content = content) }
}

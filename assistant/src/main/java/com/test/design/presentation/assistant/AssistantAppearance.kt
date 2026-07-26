package com.test.design.presentation.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** High-contrast eye rendering for sunlight / a11y. */
val LocalAssistantHighContrast = staticCompositionLocalOf { false }

val LocalAssistantHighContrastUpdater = staticCompositionLocalOf<(Boolean) -> Unit> {
    error("AssistantHighContrastUpdater not provided")
}

class AssistantAppearanceViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val key = "assistant_high_contrast"

    val highContrast: StateFlow<Boolean> = savedStateHandle
        .getStateFlow(key, false)
        .map { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = savedStateHandle[key] ?: false,
        )

    fun update(enabled: Boolean) {
        savedStateHandle[key] = enabled
    }
}

/**
 * Blend mood glow with OEM / Material brand accent for eye aura + border.
 */
@Composable
internal fun rememberAssistantBrandGlow(
    mood: AssistantMood,
    brandAccent: Color,
): Color = lerp(mood.glowColor, brandAccent, 0.45f)

internal fun eyeFillForContrast(highContrast: Boolean): Color =
    if (highContrast) Color.White else Color(0xFFF4F7FB)

internal fun auraAlphaForContrast(highContrast: Boolean, base: Float): Float =
    if (highContrast) (base * 1.55f).coerceAtMost(1f) else base

/**
 * Shell bounds used by [ImmersiveEyesFace] — EPORO / Fusion draw into this rect
 * so overall height/size matches Immersive eyes & Immersive glow.
 */
internal fun immersiveMatchedShellBounds(width: Float, height: Float): Rect {
    val side = minOf(width, height)
    val cx = width * 0.5f
    val cy = height * 0.5f
    val faceR = side * 0.36f
    val shellW = faceR * 1.38f
    val shellH = faceR * 1.42f
    return Rect(
        left = cx - shellW,
        top = cy - shellH * 0.68f,
        right = cx + shellW,
        bottom = cy + shellH * 0.72f,
    )
}

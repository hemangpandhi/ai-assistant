package com.assistant.ui.assistant.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.assistant.ui.assistant.api.AssistantContextGlyph
import kotlinx.coroutines.delay
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.ui.chrome.FaceWithThinkingCloud
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Weather sink face: weather glyphs fully replace both eyes (same capsule size + morph).
 * Thinking cloud comes from [FaceWithThinkingCloud].
 */
@Composable
fun WeatherSinkFace(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    contextGlyph: AssistantContextGlyph? = null,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = AssistantTokens.Accent,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
) {
    var showIcon by remember { mutableStateOf(false) }
    LaunchedEffect(contextGlyph) {
        showIcon = false
        if (contextGlyph == null) return@LaunchedEffect
        // One brief icon reveal per weather topic, then settle back on eyes.
        delay(2_800)
        showIcon = true
        delay(2_600)
        showIcon = false
    }

    val iconAlpha = remember { Animatable(0f) }
    LaunchedEffect(showIcon, contextGlyph) {
        if (contextGlyph != null && showIcon) {
            iconAlpha.animateTo(1f, tween(640, easing = FastOutSlowInEasing))
        } else {
            iconAlpha.animateTo(0f, tween(560, easing = FastOutSlowInEasing))
        }
    }

    FaceWithThinkingCloud(mood = mood, modifier = modifier) {
        FusionEyesAssistantFace(
            mood = mood,
            modifier = Modifier.fillMaxSize(),
            gazeX = gazeX,
            gazeY = gazeY,
            mouthAmplitude = mouthAmplitude,
            brandGlow = brandGlow,
            highContrast = highContrast,
            gesture = gesture,
            visorDisplayGlyph = contextGlyph?.imageVector(),
            visorDisplayAlpha = if (contextGlyph != null) iconAlpha.value else 0f,
            visorDisplayTint = contextGlyph?.tint(),
        )
    }
}

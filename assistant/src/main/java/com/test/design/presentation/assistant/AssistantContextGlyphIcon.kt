package com.test.design.presentation.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.test.design.assistant.api.AssistantContextGlyph

private val WeatherCool = Color(0xFF90CAF9)
private val WeatherStorm = Color(0xFF80CBC4)
private val WeatherSnow = Color(0xFFE3F2FD)
private val WeatherCloudy = Color(0xFFB0BEC5)
private val WeatherSunny = Color(0xFFFFD54F)
private val ClimateCool = Color(0xFF4DD0E1)
private val ClimateWarm = Color(0xFFFFB74D)
private val ClimateNeutral = Color(0xFFCE93D8)

internal fun AssistantContextGlyph.imageVector(): ImageVector = when (this) {
    AssistantContextGlyph.WeatherLightRain -> Icons.Outlined.WaterDrop
    AssistantContextGlyph.WeatherHeavyRain -> Icons.Outlined.Thunderstorm
    AssistantContextGlyph.WeatherSnow -> Icons.Outlined.AcUnit
    AssistantContextGlyph.WeatherCloudy -> Icons.Outlined.WbCloudy
    AssistantContextGlyph.WeatherSunny -> Icons.Outlined.WbSunny
    AssistantContextGlyph.ClimateThermostat -> Icons.Outlined.Thermostat
    AssistantContextGlyph.ClimateAc -> Icons.Outlined.AcUnit
    AssistantContextGlyph.ClimateHeat -> Icons.Outlined.Whatshot
    AssistantContextGlyph.ClimateFan -> Icons.Outlined.Air
    AssistantContextGlyph.ClimateDefrost -> Icons.Outlined.AcUnit
}

internal fun AssistantContextGlyph.tint(): Color = when (this) {
    AssistantContextGlyph.WeatherLightRain -> WeatherCool
    AssistantContextGlyph.WeatherHeavyRain -> WeatherStorm
    AssistantContextGlyph.WeatherSnow -> WeatherSnow
    AssistantContextGlyph.WeatherCloudy -> WeatherCloudy
    AssistantContextGlyph.WeatherSunny -> WeatherSunny
    AssistantContextGlyph.ClimateThermostat -> ClimateNeutral
    AssistantContextGlyph.ClimateAc -> ClimateCool
    AssistantContextGlyph.ClimateHeat -> ClimateWarm
    AssistantContextGlyph.ClimateFan -> ClimateCool
    AssistantContextGlyph.ClimateDefrost -> WeatherCool
}

/** Soft upward gaze toward a floating context glyph. */
internal fun contextGlyphGaze(): Pair<Float, Float> = 0.08f to -0.42f

/**
 * Floating Material context glyph above Fusion Eyes — one icon, spring in, soft bob.
 */
@Composable
fun AssistantContextGlyphIcon(
    glyph: AssistantContextGlyph?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val bob = rememberInfiniteTransition(label = "context_glyph_bob")
    val bobY by bob.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "context_glyph_bob_y",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(glyph) {
        if (glyph != null) {
            appear.snapTo(0f)
            appear.animateTo(
                1f,
                spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            )
        } else {
            appear.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
        }
    }

    AnimatedContent(
        targetState = glyph,
        transitionSpec = {
            (
                fadeIn(tween(220)) + scaleIn(
                    initialScale = 0.82f,
                    animationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
                ) togetherWith (
                fadeOut(tween(160)) + scaleOut(targetScale = 0.9f, animationSpec = tween(160))
                )
        },
        label = "context_glyph",
        modifier = modifier,
    ) { current ->
        if (current == null) {
            Box(modifier = Modifier.size(size)) {}
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .offset(y = bobY.dp)
                    .graphicsLayer {
                        val a = appear.value.coerceIn(0f, 1f)
                        alpha = a
                        val s = 0.86f + 0.14f * a
                        scaleX = s
                        scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = current.imageVector(),
                    contentDescription = current.name,
                    tint = current.tint(),
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}

package com.assistant.ui.assistant.face

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import com.assistant.ui.assistant.api.AssistantFaceCueIcon
import com.assistant.ui.assistant.api.FaceCueCategory
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens
import kotlin.math.max
import kotlin.math.sin

/** Compose Material vector for [AssistantFaceCueIcon] — morphable / tintable. */
fun AssistantFaceCueIcon.imageVector(): ImageVector = when (this) {
    AssistantFaceCueIcon.Rain -> Icons.Outlined.WaterDrop
    AssistantFaceCueIcon.Storm -> Icons.Outlined.Thunderstorm
    AssistantFaceCueIcon.Snow -> Icons.Outlined.AcUnit
    AssistantFaceCueIcon.Cloudy -> Icons.Outlined.WbCloudy
    AssistantFaceCueIcon.Sunny -> Icons.Outlined.WbSunny
    AssistantFaceCueIcon.Thermostat -> Icons.Outlined.Thermostat
    AssistantFaceCueIcon.Ac -> Icons.Outlined.AcUnit
    AssistantFaceCueIcon.Heat -> Icons.Outlined.Whatshot
    AssistantFaceCueIcon.Fan -> Icons.Outlined.Air
    AssistantFaceCueIcon.Defrost -> Icons.Outlined.AcUnit
    AssistantFaceCueIcon.Music -> Icons.Outlined.MusicNote
    AssistantFaceCueIcon.Podcast -> Icons.Outlined.GraphicEq
    AssistantFaceCueIcon.Mic -> Icons.Outlined.Mic
    AssistantFaceCueIcon.Search -> Icons.Outlined.Search
    AssistantFaceCueIcon.Navigate -> Icons.Outlined.Map
    AssistantFaceCueIcon.Sparkle -> Icons.Outlined.AutoAwesome
    AssistantFaceCueIcon.Star -> Icons.Outlined.StarBorder
    AssistantFaceCueIcon.Wave -> Icons.Outlined.WavingHand
    AssistantFaceCueIcon.Heart -> Icons.Outlined.FavoriteBorder
}

fun AssistantFaceCueIcon.tint(): Color = when (category) {
    FaceCueCategory.Weather -> when (this) {
        AssistantFaceCueIcon.Rain -> AssistantGlyphPalette.WeatherCool
        AssistantFaceCueIcon.Storm -> AssistantGlyphPalette.WeatherStorm
        AssistantFaceCueIcon.Snow -> AssistantGlyphPalette.WeatherSnow
        AssistantFaceCueIcon.Cloudy -> AssistantGlyphPalette.WeatherCloudy
        AssistantFaceCueIcon.Sunny -> AssistantGlyphPalette.WeatherSunny
        else -> AssistantGlyphPalette.WeatherCool
    }
    FaceCueCategory.Climate -> when (this) {
        AssistantFaceCueIcon.Heat -> AssistantGlyphPalette.ClimateWarm
        AssistantFaceCueIcon.Thermostat -> AssistantGlyphPalette.ClimateNeutral
        else -> AssistantGlyphPalette.ClimateCool
    }
    FaceCueCategory.Media -> AssistantGlyphPalette.Media
    FaceCueCategory.Nav -> AssistantGlyphPalette.Nav
    FaceCueCategory.Accent -> AssistantGlyphPalette.Accent
}

/** Fallback pale glyph tint when brand contrast wants white line-art. */
fun AssistantFaceCueIcon.glyphTint(highContrast: Boolean = false): Color =
    if (highContrast) AssistantGlyphPalette.GlyphWhite else tint()

/**
 * Draw a Material vector as a face-anatomy replacement — same *place* as the
 * geometric feature, sized for readability (not locked to capsule bounds).
 */
internal fun DrawScope.drawFaceCueIcon(
    painter: Painter,
    center: Offset,
    side: Float,
    alpha: Float,
    tint: Color,
) {
    val s = max(side, 1f)
    translate(left = center.x - s * 0.5f, top = center.y - s * 0.5f) {
        with(painter) {
            draw(
                size = Size(s, s),
                alpha = alpha.coerceIn(0f, 1f),
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

/**
 * Live cue glyph: gentle bob, pulse, and sway so icons feel active — never static stickers.
 *
 * @param life radians phase from the face idle clock
 * @param phaseOffset per-slot phase so L/R/mouth don't move in lockstep
 */
internal fun DrawScope.drawAnimatedFaceCueIcon(
    painter: Painter,
    center: Offset,
    side: Float,
    tint: Color,
    life: Float,
    phaseOffset: Float = 0f,
) {
    val bob = sin(life * 1.55f + phaseOffset).toFloat() * side * 0.10f
    val sway = sin(life * 1.05f + phaseOffset * 0.8f).toFloat() * side * 0.05f
    val pulse = 1f + 0.10f * sin(life * 2.2f + phaseOffset * 0.6f).toFloat()
    val alpha = (
        0.78f + 0.22f * (0.5f + 0.5f * sin(life * 1.8f + phaseOffset).toFloat())
        ).coerceIn(0.55f, 1f)
    val degrees = sin(life * 0.95f + phaseOffset).toFloat() * 9f
    val pivoted = Offset(center.x + sway, center.y + bob)
    rotate(degrees = degrees, pivot = pivoted) {
        scale(scale = pulse, pivot = pivoted) {
            drawFaceCueIcon(painter, pivoted, side, alpha, tint)
        }
    }
}

/**
 * Island capsule badge: near-capsule-height borderless plate to the **right** of the eyes.
 * Same dark-grey tone as the eye face shell; any cue slot (eyes / mouth / accents) can
 * drive this badge — geometric eye capsules are never replaced.
 */
internal fun DrawScope.drawIslandEyeCueBadge(
    painter: Painter,
    center: Offset,
    radius: Float,
    tint: Color,
    life: Float,
) {
    val r = radius.coerceAtLeast(1f)
    // Match the eye face-shell tone so the cue circle reads as a sibling plate.
    drawCircle(
        color = AssistantOverlayTokens.IslandFaceShell,
        radius = r,
        center = center,
    )
    // Icon sits inside the plate with a little inset (not larger than the circle).
    drawAnimatedFaceCueIcon(
        painter = painter,
        center = center,
        side = r * 1.15f,
        tint = tint,
        life = life,
        phaseOffset = 0.45f,
    )
}

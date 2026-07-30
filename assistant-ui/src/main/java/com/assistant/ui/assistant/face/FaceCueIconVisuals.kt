package com.assistant.ui.assistant.face

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import com.assistant.ui.assistant.api.AssistantFaceCueIcon
import com.assistant.ui.assistant.api.FaceCueCategory
import kotlin.math.max

private val WeatherCool = Color(0xFF90CAF9)
private val WeatherStorm = Color(0xFF80CBC4)
private val WeatherSnowTint = Color(0xFFE3F2FD)
private val WeatherCloudyTint = Color(0xFFB0BEC5)
private val WeatherSunnyTint = Color(0xFFFFD54F)
private val ClimateCool = Color(0xFF4DD0E1)
private val ClimateWarm = Color(0xFFFFB74D)
private val ClimateNeutral = Color(0xFFCE93D8)
private val MediaTint = Color(0xFFE1BEE7)
private val NavTint = Color(0xFF80CBC4)
private val AccentTint = Color(0xFFFFF59D)
private val GlyphWhite = Color(0xFFF5F7FA)

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
    AssistantFaceCueIcon.Navigate -> Icons.Outlined.Navigation
    AssistantFaceCueIcon.Sparkle -> Icons.Outlined.AutoAwesome
    AssistantFaceCueIcon.Star -> Icons.Outlined.StarBorder
    AssistantFaceCueIcon.Wave -> Icons.Outlined.WavingHand
    AssistantFaceCueIcon.Heart -> Icons.Outlined.FavoriteBorder
}

fun AssistantFaceCueIcon.tint(): Color = when (category) {
    FaceCueCategory.Weather -> when (this) {
        AssistantFaceCueIcon.Rain -> WeatherCool
        AssistantFaceCueIcon.Storm -> WeatherStorm
        AssistantFaceCueIcon.Snow -> WeatherSnowTint
        AssistantFaceCueIcon.Cloudy -> WeatherCloudyTint
        AssistantFaceCueIcon.Sunny -> WeatherSunnyTint
        else -> WeatherCool
    }
    FaceCueCategory.Climate -> when (this) {
        AssistantFaceCueIcon.Heat -> ClimateWarm
        AssistantFaceCueIcon.Thermostat -> ClimateNeutral
        else -> ClimateCool
    }
    FaceCueCategory.Media -> MediaTint
    FaceCueCategory.Nav -> NavTint
    FaceCueCategory.Accent -> AccentTint
}

/** Fallback pale glyph tint when brand contrast wants white line-art. */
fun AssistantFaceCueIcon.glyphTint(highContrast: Boolean = false): Color =
    if (highContrast) GlyphWhite else tint()

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

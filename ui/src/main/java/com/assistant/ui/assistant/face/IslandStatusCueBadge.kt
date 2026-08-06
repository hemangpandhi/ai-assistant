package com.assistant.ui.assistant.face

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.api.AssistantFaceCueIcon
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens

/**
 * Island status circle — Compose sibling of the eyes (not drawn under the face shell).
 * Used for music / weather / climate / nav cues resolved via [AssistantFaceCues.islandStatusIcon].
 */
@Composable
fun IslandStatusCueBadge(
    icon: AssistantFaceCueIcon,
    modifier: Modifier = Modifier,
    size: Dp = AssistantOverlayTokens.IslandCueBadgeSize,
    highContrast: Boolean = false,
) {
    val tint = icon.glyphTint(highContrast)
    Box(
        modifier = modifier
            .size(size)
            .background(AssistantOverlayTokens.IslandCueBadgeFill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.imageVector(),
            contentDescription = icon.label,
            tint = tint,
            modifier = Modifier
                .size(size * 0.58f)
                .padding(1.dp),
        )
    }
}

package com.assistant.ui.assistant.ui.immersive

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens

/** Morph size class for the Dynamic Island capsule. */
enum class IslandSizeClass {
    Compact,
    Listening,
    Expanded,
}

/**
 * Resolves island size from mood + whether spoken text should appear inside the pill.
 * Idle / listening / thinking stay on the compact frame; only transcript expands.
 */
fun resolveIslandSizeClass(mood: AssistantMood, hasTranscript: Boolean): IslandSizeClass =
    if (hasTranscript) IslandSizeClass.Expanded else IslandSizeClass.Compact

/**
 * Gemini-style width estimate for short single-line transcripts: face slot + text + pads,
 * clamped to [min, max]. Longer lines grow the pill horizontally instead of taller.
 */
fun estimateIslandExpandedWidth(
    charCount: Int,
    maxWidth: Dp,
    minWidth: Dp = AssistantOverlayTokens.IslandExpandedWidthMin,
    hardMax: Dp = AssistantOverlayTokens.IslandExpandedWidthMax,
): Dp {
    val faceSlot = AssistantOverlayTokens.IslandFaceExpanded
    val pads = AssistantOverlayTokens.IslandExpandedPadStart +
        AssistantOverlayTokens.IslandExpandedPadEnd +
        AssistantOverlayTokens.IslandFaceTextGap
    // ~14dp per glyph at ImmersiveTranscript's 26sp semibold.
    val textW = (charCount.coerceAtLeast(1) * 14).dp.coerceAtLeast(72.dp)
    return (faceSlot + pads + textW)
        .coerceIn(minWidth, hardMax)
        .coerceAtMost(maxWidth)
}

/**
 * Dynamic Island–style horizontal capsule: black fill, thin outer frame,
 * morphing width/height, face + optional transcript inside.
 *
 * Idle: compact capsule (see [AssistantOverlayTokens.IslandCompactWidth]).
 * With transcript: widens horizontally (Gemini-style); face left, text right.
 */
@Composable
fun IslandCapsuleDock(
    mood: AssistantMood,
    hasTranscript: Boolean,
    modifier: Modifier = Modifier,
    transcriptCharCount: Int = 0,
    contentAlpha: Float = 1f,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
    face: @Composable (faceSize: Dp) -> Unit,
    transcript: (@Composable () -> Unit)? = null,
) {
    val sizeClass = resolveIslandSizeClass(mood, hasTranscript)
    val listening = mood == AssistantMood.Listening
    // Frame softens/brightens with shared rim breath.
    val breathMul = (0.72f + glowBreath.inhale * 0.38f + (glowBreath.fade - 0.62f) * 0.25f)
        .coerceIn(0.65f, 1.15f)
    val transition = updateTransition(targetState = sizeClass, label = "island_size")

    BoxWithConstraints(modifier = modifier) {
        val expandedW = estimateIslandExpandedWidth(
            charCount = transcriptCharCount,
            maxWidth = (maxWidth * AssistantOverlayTokens.IslandExpandedWidthFraction)
                .coerceIn(
                    AssistantOverlayTokens.IslandExpandedWidthMin,
                    AssistantOverlayTokens.IslandExpandedWidthMax,
                )
                .coerceAtMost(maxWidth),
        )

        val width by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "island_w",
        ) { sc ->
            when (sc) {
                IslandSizeClass.Compact -> AssistantOverlayTokens.IslandCompactWidth
                IslandSizeClass.Listening -> AssistantOverlayTokens.IslandListeningWidth
                IslandSizeClass.Expanded -> expandedW
            }
        }
        val height by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
            },
            label = "island_h",
        ) { sc ->
            when (sc) {
                IslandSizeClass.Compact -> AssistantOverlayTokens.IslandCompactHeight
                IslandSizeClass.Listening -> AssistantOverlayTokens.IslandListeningHeight
                IslandSizeClass.Expanded -> AssistantOverlayTokens.IslandExpandedHeightWithText
            }
        }
        val faceSize by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            },
            label = "island_face",
        ) { sc ->
            when (sc) {
                IslandSizeClass.Compact -> AssistantOverlayTokens.IslandFaceCompact
                IslandSizeClass.Listening -> AssistantOverlayTokens.IslandFaceListening
                IslandSizeClass.Expanded -> AssistantOverlayTokens.IslandFaceExpanded
            }
        }
        val showTranscriptInside by transition.animateFloat(
            transitionSpec = {
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
            },
            label = "island_transcript_alpha",
        ) { sc ->
            if (hasTranscript && transcript != null && sc == IslandSizeClass.Expanded) 1f else 0f
        }
        // True capsule: corner radius tracks half the animated height.
        val corner = height / 2

        val fill = AssistantOverlayTokens.IslandFill
        val frameColor = if (listening) {
            AssistantOverlayTokens.IslandListeningFrame
        } else {
            AssistantOverlayTokens.IslandFrame
        }
        val shape = RoundedCornerShape(corner)
        val showRow = showTranscriptInside > 0.02f && transcript != null
        val listenGlow = if (listening) 1f else 0f

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(width)
                    .height(height)
                    .drawBehind {
                        if (listenGlow < 0.01f) return@drawBehind
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.5f
                        val radius = size.width * 0.55f
                        val glow = AssistantOverlayTokens.IslandListeningGlow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.00f to glow.copy(alpha = 0.45f * listenGlow),
                                    0.55f to glow.copy(alpha = 0.18f * listenGlow),
                                    1.00f to glow.copy(alpha = 0f),
                                ),
                                center = Offset(cx, cy),
                                radius = radius,
                            ),
                            center = Offset(cx, cy),
                            radius = radius,
                        )
                    }
                    .background(fill, shape)
                    .border(
                        width = AssistantOverlayTokens.IslandFrameStroke,
                        color = frameColor.copy(
                            alpha = (0.72f * breathMul).coerceIn(0.55f, 1f),
                        ),
                        shape = shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (showRow) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = AssistantOverlayTokens.IslandExpandedPadStart,
                                end = AssistantOverlayTokens.IslandExpandedPadEnd,
                            )
                            .graphicsLayer { alpha = showTranscriptInside },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            AssistantOverlayTokens.IslandFaceTextGap,
                        ),
                    ) {
                        Box(
                            modifier = Modifier.size(faceSize),
                            contentAlignment = Alignment.Center,
                        ) {
                            face(faceSize)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            transcript()
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        face(faceSize)
                    }
                }
            }
        }
    }
}

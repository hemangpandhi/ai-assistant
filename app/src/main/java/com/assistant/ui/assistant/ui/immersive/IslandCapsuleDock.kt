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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens

/** Soft panel blue from [ImmersiveBorderGlow] spectrum — not OEM purple. */
private val BorderPanelBlue = Color(0xFF8AB4F8)
private val BorderIceBlue = Color(0xFF6EC8FF)

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
    faceSlot: Dp,
    minWidth: Dp = AssistantOverlayTokens.IslandExpandedWidthMin,
    hardMax: Dp = AssistantOverlayTokens.IslandExpandedWidthMax,
): Dp {
    val pads = AssistantOverlayTokens.IslandExpandedPadStart +
        AssistantOverlayTokens.IslandExpandedPadEnd +
        AssistantOverlayTokens.IslandFaceTextGap
    // ~14dp per glyph at ImmersiveTranscript's 26sp semibold.
    val textW = (charCount.coerceAtLeast(1) * 14).dp.coerceAtLeast(72.dp)
    return (faceSlot + pads + textW)
        .coerceIn(minWidth.coerceAtLeast(faceSlot + pads), hardMax)
        .coerceAtMost(maxWidth)
}

/**
 * Dynamic Island–style horizontal capsule: black fill, thin outer frame,
 * morphing width/height, face + optional transcript inside.
 *
 * Soft blue radial breath plate (ex-[FaceStageDock]) sits behind the pill.
 * Idle size tracks the stage face; with transcript the pill widens for text.
 */
@Composable
fun IslandCapsuleDock(
    mood: AssistantMood,
    hasTranscript: Boolean,
    faceSizeCompact: Dp,
    modifier: Modifier = Modifier,
    faceSizeExpanded: Dp = faceSizeCompact * 0.82f,
    brandGlow: Color = BorderPanelBlue,
    transcriptCharCount: Int = 0,
    contentAlpha: Float = 1f,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
    face: @Composable (faceSize: Dp) -> Unit,
    transcript: (@Composable () -> Unit)? = null,
) {
    val sizeClass = resolveIslandSizeClass(mood, hasTranscript)
    val listening = mood == AssistantMood.Listening
    // Same inhale cue FaceStageDock used on the semicircle plate.
    val breathMul = (0.72f + glowBreath.inhale * 0.38f + (glowBreath.fade - 0.62f) * 0.25f)
        .coerceIn(0.65f, 1.15f)
    val blue = remember(brandGlow) {
        val brand = brandGlow.copy(alpha = 1f)
        lerp(BorderPanelBlue, lerp(BorderIceBlue, brand, 0.20f), 0.18f)
    }
    val transition = updateTransition(targetState = sizeClass, label = "island_size")

    BoxWithConstraints(modifier = modifier) {
        val compactH = (faceSizeCompact + 24.dp)
            .coerceAtLeast(AssistantOverlayTokens.IslandCompactHeight)
        val compactW = (faceSizeCompact + 48.dp)
            .coerceAtLeast(AssistantOverlayTokens.IslandCompactWidth)
            .coerceAtMost(maxWidth)
        val expandedH = (faceSizeExpanded + 32.dp)
            .coerceAtLeast(AssistantOverlayTokens.IslandExpandedHeightWithText)
        val expandedW = estimateIslandExpandedWidth(
            charCount = transcriptCharCount,
            maxWidth = (maxWidth * AssistantOverlayTokens.IslandExpandedWidthFraction)
                .coerceIn(
                    AssistantOverlayTokens.IslandExpandedWidthMin,
                    AssistantOverlayTokens.IslandExpandedWidthMax,
                )
                .coerceAtMost(maxWidth),
            faceSlot = faceSizeExpanded,
            minWidth = compactW,
        )

        val width by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
            },
            label = "island_w",
        ) { sc ->
            when (sc) {
                IslandSizeClass.Compact -> compactW
                IslandSizeClass.Listening -> compactW
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
                IslandSizeClass.Compact -> compactH
                IslandSizeClass.Listening -> compactH
                IslandSizeClass.Expanded -> expandedH
            }
        }
        val faceSize by transition.animateDp(
            transitionSpec = {
                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            },
            label = "island_face",
        ) { sc ->
            when (sc) {
                IslandSizeClass.Compact -> faceSizeCompact
                IslandSizeClass.Listening -> faceSizeCompact
                IslandSizeClass.Expanded -> faceSizeExpanded
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
        val peak = (0.16f * breathMul).coerceIn(0.10f, 0.22f)
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
                    // Soft blue radial breath plate behind the pill (ex-FaceStageDock).
                    .drawBehind {
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.55f
                        val radius = size.width * 0.62f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.00f to blue.copy(alpha = peak),
                                    0.28f to blue.copy(alpha = peak * 0.62f),
                                    0.52f to blue.copy(alpha = peak * 0.34f),
                                    0.74f to blue.copy(alpha = peak * 0.14f),
                                    0.90f to blue.copy(alpha = peak * 0.04f),
                                    1.00f to blue.copy(alpha = 0.00f),
                                ),
                                center = Offset(cx, cy),
                                radius = radius,
                            ),
                            center = Offset(cx, cy),
                            radius = radius,
                        )
                        if (listenGlow > 0.01f) {
                            val glow = AssistantOverlayTokens.IslandListeningGlow
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.00f to glow.copy(alpha = 0.55f * listenGlow),
                                        0.55f to glow.copy(alpha = 0.22f * listenGlow),
                                        1.00f to glow.copy(alpha = 0f),
                                    ),
                                    center = Offset(cx, cy),
                                    radius = radius * 0.95f,
                                ),
                                center = Offset(cx, cy),
                                radius = radius * 0.95f,
                            )
                        }
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
                    // Gemini-style: eyes/face left, single-line transcript to the right.
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
                    // Compact: geometrically center the face in the pill.
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

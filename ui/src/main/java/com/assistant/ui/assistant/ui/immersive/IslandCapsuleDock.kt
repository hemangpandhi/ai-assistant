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
 * clamped to [minWidth, maxWidth]. [maxWidth] is the stage cap (50% of available width);
 * longer lines stop widening there and [LiveInputText] autoscrolls inside the text slot.
 *
 * When [hasStatusCue] is true the face slot widens for eyes + near-capsule-height badge.
 */
fun estimateIslandExpandedWidth(
    charCount: Int,
    maxWidth: Dp,
    minWidth: Dp = AssistantOverlayTokens.IslandExpandedWidthMin,
    hasStatusCue: Boolean = false,
): Dp {
    val faceSlot = if (hasStatusCue) {
        // Eyes band + near-height badge — matches expanded face slot below.
        AssistantOverlayTokens.IslandExpandedHeightWithText * 1.35f
    } else {
        // Wide band so the grey eye shell stays a horizontal capsule (not a circle).
        AssistantOverlayTokens.IslandFaceExpandedWidth
    }
    val pads = AssistantOverlayTokens.IslandExpandedPadStart +
        AssistantOverlayTokens.IslandExpandedPadEnd +
        AssistantOverlayTokens.IslandFaceTextGap
    // ~14dp per glyph at ImmersiveTranscript's 26sp semibold.
    val textW = (charCount.coerceAtLeast(1) * 14).dp.coerceAtLeast(72.dp)
    return (faceSlot + pads + textW).coerceIn(minWidth, maxWidth)
}

/**
 * Dynamic Island–style horizontal capsule: black fill, thin outer frame,
 * morphing width/height, face + optional transcript inside.
 *
 * The capsule itself breathes subtly with [glowBreath] (same inhale as the rim /
 * earlier face dock) — no separate plate behind the pill.
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
    /** When true, face slot fills capsule height for the near-height status cue circle. */
    hasStatusCue: Boolean = false,
    contentAlpha: Float = 1f,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
    face: @Composable (faceSize: Dp) -> Unit,
    transcript: (@Composable () -> Unit)? = null,
) {
    val sizeClass = resolveIslandSizeClass(mood, hasTranscript)
    val listening = mood == AssistantMood.Listening
    // Same inhale cue the earlier face dock used — subtle on the capsule body.
    val breathMul = (0.72f + glowBreath.inhale * 0.38f + (glowBreath.fade - 0.62f) * 0.25f)
        .coerceIn(0.65f, 1.15f)
    // ~±3% scale so the pill itself inhales without reading as a bounce.
    val capsuleBreathScale = 1f + glowBreath.inhale * 0.03f
    val transition = updateTransition(targetState = sizeClass, label = "island_size")

    BoxWithConstraints(modifier = modifier) {
        // Grow with text up to 50% of available stage width; overflow autoscrolls in-slot.
        val expandedCap = (maxWidth * AssistantOverlayTokens.IslandExpandedWidthFraction)
            .coerceAtLeast(AssistantOverlayTokens.IslandExpandedWidthMin)
            .coerceAtMost(maxWidth)
        val expandedW = estimateIslandExpandedWidth(
            charCount = transcriptCharCount,
            maxWidth = expandedCap,
            hasStatusCue = hasStatusCue,
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
                    .graphicsLayer {
                        scaleX = capsuleBreathScale
                        scaleY = capsuleBreathScale
                    }
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
                            modifier = if (hasStatusCue) {
                                // Tall status circle needs the full pill height + width for eyes.
                                Modifier
                                    .fillMaxHeight()
                                    .width(height * 1.35f)
                            } else {
                                // Wide horizontal band — shell tracks eyes as a capsule, not a 1:1 circle.
                                Modifier
                                    .fillMaxHeight()
                                    .width(AssistantOverlayTokens.IslandFaceExpandedWidth)
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            face(faceSize)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // Extra air so glyphs don’t hug the eye slot or trailing curve.
                                .padding(
                                    start = 4.dp,
                                    end = 4.dp,
                                ),
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

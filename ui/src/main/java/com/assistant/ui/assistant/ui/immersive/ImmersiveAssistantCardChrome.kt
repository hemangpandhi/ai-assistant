package com.assistant.ui.assistant.ui.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.face.AssistantFaceKind
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.face.ConfigurableAssistantFace
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.ui.chrome.assistantChromePadding
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Card-hosted immersive chrome: face + transcript inside a glass panel that
 * sits on the left, right, or bottom edge instead of the full-screen bottom band.
 */
@Composable
fun ImmersiveAssistantCardChrome(
    placement: AssistantPlacement,
    mood: AssistantMood,
    faceKind: AssistantFaceKind,
    transcript: String,
    speaker: DialogueSpeaker,
    modifier: Modifier = Modifier,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = AssistantTokens.Accent,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    showFace: Boolean = true,
    faceScale: Float = 1f,
    faceAlpha: Float = 1f,
    transcriptAlpha: Float = 1f,
    faceCues: AssistantFaceCues? = null,
    /** 0 = off-screen, 1 = settled — drives edge slide. */
    reveal: Float = 1f,
) {
    require(placement.isCard) { "ImmersiveAssistantCardChrome requires a card placement" }

    val align = when (placement) {
        AssistantPlacement.LeftCard -> Alignment.CenterStart
        AssistantPlacement.RightCard -> Alignment.CenterEnd
        AssistantPlacement.BottomCard -> Alignment.BottomCenter
        AssistantPlacement.Fullscreen -> Alignment.BottomCenter
    }
    val slideX = when (placement) {
        AssistantPlacement.LeftCard -> (1f - reveal) * -1f
        AssistantPlacement.RightCard -> (1f - reveal) * 1f
        else -> 0f
    }
    val slideY = when (placement) {
        AssistantPlacement.BottomCard -> (1f - reveal) * 1f
        else -> 0f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .assistantChromePadding()
            .padding(AssistantOverlayTokens.CardEdgePadding),
        contentAlignment = align,
    ) {
        val sideWidth = (maxWidth * AssistantOverlayTokens.CardSideWidthFraction)
            .coerceIn(AssistantOverlayTokens.CardSideWidthMin, AssistantOverlayTokens.CardSideWidthMax)
        val bottomWidth = (maxWidth * AssistantOverlayTokens.CardBottomWidthFraction)
            .coerceIn(AssistantOverlayTokens.CardBottomWidthMin, AssistantOverlayTokens.CardBottomWidthMax)
        val bottomHeight = (maxHeight * AssistantOverlayTokens.CardBottomHeightFraction)
            .coerceIn(AssistantOverlayTokens.CardBottomHeightMin, AssistantOverlayTokens.CardBottomHeightMax)
        val faceSize: Dp = when (placement) {
            AssistantPlacement.BottomCard -> AssistantOverlayTokens.CardBottomFaceSize
            else -> AssistantOverlayTokens.CardSideFaceSize
        }

        val cardModifier = when (placement) {
            AssistantPlacement.LeftCard,
            AssistantPlacement.RightCard,
            -> Modifier
                .width(sideWidth)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
            AssistantPlacement.BottomCard -> Modifier
                .widthIn(max = bottomWidth)
                .fillMaxWidth(AssistantOverlayTokens.CardBottomFillWidth)
                .height(bottomHeight)
                .padding(bottom = 8.dp)
            AssistantPlacement.Fullscreen -> Modifier
        }

        Box(
            modifier = cardModifier
                .graphicsLayer {
                    val w = size.width
                    val h = size.height
                    translationX = slideX * (if (w > 0f) w else 1f)
                    translationY = slideY * (if (h > 0f) h else 1f)
                    alpha = (0.25f + 0.75f * reveal).coerceIn(0f, 1f)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* keep session alive */ },
                ),
        ) {
            PlacementCardSurface(brandGlow = brandGlow) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = 20.dp,
                            vertical = if (placement == AssistantPlacement.BottomCard) 16.dp else 24.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = when (placement) {
                        AssistantPlacement.BottomCard -> Arrangement.Center
                        else -> Arrangement.Top
                    },
                ) {
                    if (showFace && faceKind != AssistantFaceKind.None) {
                        ConfigurableAssistantFace(
                            mood = mood,
                            kind = faceKind,
                            modifier = Modifier
                                .size(faceSize)
                                .graphicsLayer {
                                    val s = faceScale
                                    scaleX = s
                                    scaleY = s
                                    alpha = faceAlpha.coerceIn(0f, 1f)
                                },
                            gazeX = gazeX,
                            gazeY = gazeY,
                            mouthAmplitude = mouthAmplitude,
                            brandGlow = brandGlow,
                            highContrast = highContrast,
                            gesture = gesture,
                            faceCues = faceCues,
                        )
                    }
                    ImmersiveTranscript(
                        text = transcript,
                        speaker = speaker,
                        live = speaker == DialogueSpeaker.User && mood == AssistantMood.Listening,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = transcriptAlpha.coerceIn(0f, 1f) }
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacementCardSurface(
    brandGlow: Color,
    corner: Dp = AssistantOverlayTokens.CardCorner,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val edge = brandGlow.copy(alpha = 0.35f).compositeOverWhiteEdge()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        AssistantTokens.SurfaceTop,
                        AssistantTokens.Surface,
                    ),
                ),
                shape,
            )
            .border(1.dp, edge, shape),
        content = { content() },
    )
}

private fun Color.compositeOverWhiteEdge(): Color =
    Color(
        red = (red * 0.55f + 1f * 0.45f).coerceIn(0f, 1f),
        green = (green * 0.55f + 1f * 0.45f).coerceIn(0f, 1f),
        blue = (blue * 0.55f + 1f * 0.45f).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0.12f, 0.55f),
    )

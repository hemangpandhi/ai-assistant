package com.assistant.ui.assistant.ui.immersive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.api.AssistantContextGlyph
import com.assistant.ui.assistant.api.AssistantFaceCues
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.dialogue.LiveInputText
import com.assistant.ui.assistant.face.AssistantContextGlyphIcon
import com.assistant.ui.assistant.face.AssistantFaceKind
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.face.ClassicHybridEyesFace
import com.assistant.ui.assistant.face.ConfigurableAssistantFace
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.ui.chrome.assistantChromePadding
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens
import com.assistant.ui.assistant.ui.theme.AssistantTokens
import kotlin.math.roundToInt

/**
 * Master-style bottom chrome for [AssistantFaceKind.ClassicHybrid].
 *
 * SemiCircle hybrid face on [FaceStageDock]; spoken text sits **below** in a
 * text-only island capsule (face stays outside the pill).
 */
@Composable
fun ClassicHybridAssistantBottomChrome(
    mood: AssistantMood,
    transcript: String,
    speaker: DialogueSpeaker,
    modifier: Modifier = Modifier,
    faceKind: AssistantFaceKind = AssistantFaceKind.ClassicHybrid,
    gazeX: Float? = null,
    gazeY: Float? = null,
    mouthAmplitude: Float? = null,
    brandGlow: Color = AssistantTokens.Accent,
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    contextGlyph: AssistantContextGlyph? = null,
    floatContextGlyph: Boolean = false,
    showFace: Boolean = true,
    faceRise: Float = 0f,
    faceScale: Float = 1f,
    faceAlpha: Float = 1f,
    transcriptAlpha: Float = 1f,
    faceSizeScale: Float = 1f,
    faceCues: AssistantFaceCues? = null,
    faceContent: (@Composable (faceModifier: Modifier, faceSize: Dp) -> Unit)? = null,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
) {
    val showGlyph = floatContextGlyph &&
        faceKind == AssistantFaceKind.FusionEyes &&
        contextGlyph != null &&
        faceContent == null
    val hasTranscript = transcript.isNotBlank()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .assistantChromePadding()
            .padding(
                start = AssistantOverlayTokens.BottomChromePaddingStart,
                top = AssistantOverlayTokens.BottomChromePaddingTop,
                end = AssistantOverlayTokens.BottomChromePaddingEnd,
                bottom = 0.dp,
            ),
    ) {
        val faceSize = (maxHeight * AssistantOverlayTokens.FaceStageHeightFraction * faceSizeScale)
            .coerceIn(AssistantOverlayTokens.FaceSizeMin, AssistantOverlayTokens.FaceSizeMax)
        val glyphSize = (faceSize * AssistantOverlayTokens.GlyphSizeFraction)
            .coerceIn(AssistantOverlayTokens.GlyphSizeMin, AssistantOverlayTokens.GlyphSizeMax)
        val density = LocalDensity.current
        val belowPx = with(density) {
            (maxHeight * AssistantOverlayTokens.FaceBelowTravelFraction).toPx()
        }
        val entranceY = when {
            faceRise >= 0f -> 0
            else -> (-faceRise * belowPx).roundToInt()
        }
        val estimatedStack = faceSize + AssistantOverlayTokens.EstimatedStackExtra
        val dockWidth = maxOf(
            faceSize * AssistantOverlayTokens.DockWidthFaceMul,
            estimatedStack * AssistantOverlayTokens.DockWidthStackMul,
        ).coerceIn(AssistantOverlayTokens.DockWidthMin, maxWidth)
        val dockAlpha = maxOf(faceAlpha, transcriptAlpha).coerceIn(0f, 1f)
        val textCapsuleMaxWidth = (maxWidth * AssistantOverlayTokens.IslandExpandedWidthFraction)
            .coerceAtLeast(AssistantOverlayTokens.IslandExpandedWidthMin)
            .coerceAtMost(maxWidth)

        FaceStageDock(
            brandGlow = brandGlow,
            width = dockWidth,
            contentAlpha = dockAlpha,
            glowBreath = glowBreath,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AssistantOverlayTokens.BottomChromeDockPadding)
                .offset { IntOffset(0, entranceY) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* keep session alive */ },
                ),
        ) {
            if (showFace && faceKind != AssistantFaceKind.None) {
                val faceNudge = (faceSize * AssistantOverlayTokens.FaceTowardTranscriptNudgeFraction)
                    .coerceAtLeast(0.dp)
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .padding(top = if (showGlyph) glyphSize * 0.72f else 0.dp)
                        .padding(bottom = AssistantOverlayTokens.FaceTranscriptGap)
                        .offset {
                            IntOffset(0, faceNudge.roundToPx())
                        }
                        .graphicsLayer {
                            val s = faceScale
                            scaleX = s
                            scaleY = s
                            alpha = 1f
                        },
                ) {
                    if (showGlyph) {
                        AssistantContextGlyphIcon(
                            glyph = contextGlyph,
                            size = glyphSize,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = -(glyphSize * 0.72f)),
                        )
                    }
                    when {
                        faceContent != null -> faceContent(Modifier.size(faceSize), faceSize)
                        faceKind == AssistantFaceKind.ClassicHybrid -> ClassicHybridEyesFace(
                            mood = mood,
                            modifier = Modifier.size(faceSize),
                            gazeX = gazeX,
                            gazeY = gazeY,
                            mouthAmplitude = mouthAmplitude,
                            brandGlow = brandGlow,
                            highContrast = highContrast,
                            gesture = gesture,
                            faceCues = faceCues,
                        )
                        else -> ConfigurableAssistantFace(
                            mood = mood,
                            kind = faceKind,
                            modifier = Modifier.size(faceSize),
                            gazeX = gazeX,
                            gazeY = gazeY,
                            mouthAmplitude = mouthAmplitude,
                            brandGlow = brandGlow,
                            highContrast = highContrast,
                            gesture = gesture,
                            faceCues = faceCues,
                            showShell = true,
                        )
                    }
                }
            }
            ClassicHybridTextCapsule(
                text = transcript,
                speaker = speaker,
                mood = mood,
                visible = hasTranscript,
                maxWidth = textCapsuleMaxWidth,
                glowBreath = glowBreath,
                modifier = Modifier
                    .graphicsLayer { alpha = transcriptAlpha.coerceIn(0f, 1f) },
            )
        }
    }
}

/**
 * Island-styled capsule for classic-hybrid transcript only (no face inside).
 * Width grows with text up to [maxWidth]; hidden when [visible] is false.
 */
@Composable
fun ClassicHybridTextCapsule(
    text: String,
    speaker: DialogueSpeaker,
    mood: AssistantMood,
    visible: Boolean,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
) {
    val listening = mood == AssistantMood.Listening
    val targetWidth = estimateClassicHybridTextCapsuleWidth(
        charCount = text.length,
        maxWidth = maxWidth,
    )
    val width by animateDpAsState(
        targetValue = if (visible) targetWidth else AssistantOverlayTokens.IslandExpandedWidthMin,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "classic_hybrid_text_capsule_w",
    )
    val height = AssistantOverlayTokens.IslandExpandedHeightWithText
    val corner = height / 2
    val shape = RoundedCornerShape(corner)
    val fill = AssistantOverlayTokens.IslandFill
    val frameColor = if (listening) {
        AssistantOverlayTokens.IslandListeningFrame
    } else {
        AssistantOverlayTokens.IslandFrame
    }
    val breathMul = (0.72f + glowBreath.inhale * 0.38f + (glowBreath.fade - 0.62f) * 0.25f)
        .coerceIn(0.65f, 1.15f)
    val capsuleBreathScale = 1f + glowBreath.inhale * 0.03f

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
        ),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .widthIn(max = maxWidth)
                .height(height)
                .graphicsLayer {
                    scaleX = capsuleBreathScale
                    scaleY = capsuleBreathScale
                }
                .background(fill, shape)
                .border(
                    width = AssistantOverlayTokens.IslandFrameStroke,
                    color = frameColor.copy(
                        alpha = (0.72f * breathMul).coerceIn(0.55f, 1f),
                    ),
                    shape = shape,
                )
                .padding(
                    start = AssistantOverlayTokens.IslandExpandedPadStart,
                    end = AssistantOverlayTokens.IslandExpandedPadEnd,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ClassicHybridTranscript(
                text = text,
                speaker = speaker,
                live = speaker == DialogueSpeaker.User && listening,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Text-only capsule width: pads + ~14dp/glyph, clamped to [maxWidth]. */
fun estimateClassicHybridTextCapsuleWidth(
    charCount: Int,
    maxWidth: Dp,
    minWidth: Dp = AssistantOverlayTokens.IslandExpandedWidthMin,
): Dp {
    val pads = AssistantOverlayTokens.IslandExpandedPadStart +
        AssistantOverlayTokens.IslandExpandedPadEnd
    val textW = (charCount.coerceAtLeast(1) * 14).dp.coerceAtLeast(72.dp)
    return (pads + textW).coerceIn(minWidth, maxWidth)
}

/**
 * Centered transcript inside the classic-hybrid text capsule.
 */
@Composable
fun ClassicHybridTranscript(
    text: String,
    speaker: DialogueSpeaker,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = speaker,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "classic_hybrid_transcript_speaker",
            modifier = Modifier.fillMaxWidth(),
        ) { who ->
            val bodyColor = when (who) {
                DialogueSpeaker.User -> Color(0xFFD2E3FC)
                DialogueSpeaker.Assistant -> Color(0xFFF8F9FA)
                DialogueSpeaker.System -> Color(0xFFBDC1C6)
            }
            LiveInputText(
                text = text,
                color = bodyColor,
                live = live && who == DialogueSpeaker.User,
                speaking = who == DialogueSpeaker.Assistant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

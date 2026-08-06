package com.assistant.ui.assistant.ui.immersive

import com.assistant.ui.assistant.ui.chrome.assistantChromePadding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.api.AssistantContextGlyph
import com.assistant.ui.assistant.api.AssistantFaceCues
import kotlin.math.roundToInt
import com.assistant.ui.assistant.face.AssistantContextGlyphIcon
import com.assistant.ui.assistant.face.AssistantFaceKind
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.face.ConfigurableAssistantFace
import com.assistant.ui.assistant.face.IslandStatusCueBadge
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Shared bottom chrome for the immersive assistant stage.
 *
 * Default: Dynamic Island capsule + eyes-in-pill + transcript inside the pill.
 * [AssistantFaceKind.ClassicHybrid]: master-style [FaceStageDock] — SemiCircle
 * face with transcript below (no capsule).
 *
 * [faceRise] entrance lift: -1 = off-screen below, 0 = settled.
 *
 * [faceContent] replaces [ConfigurableAssistantFace] when provided (e.g. Weather sink).
 * [floatContextGlyph] shows the Material icon above Fusion Eyes; Weather sink keeps this off
 * and swaps the icon into the eye band instead.
 * [faceCues] drives in-face Material icons on the main overlay (LLM-owned).
 */
@Composable
fun ImmersiveAssistantBottomChrome(
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
    contextGlyph: AssistantContextGlyph? = null,
    floatContextGlyph: Boolean = true,
    showFace: Boolean = true,
    faceRise: Float = 0f,
    faceScale: Float = 1f,
    faceAlpha: Float = 1f,
    transcriptAlpha: Float = 1f,
    /** Multiplier on island / stage face size (e.g. 1.05f for Weather sink). */
    faceSizeScale: Float = 1f,
    faceCues: AssistantFaceCues? = null,
    faceContent: (@Composable (faceModifier: Modifier, faceSize: Dp) -> Unit)? = null,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
) {
    if (faceKind == AssistantFaceKind.ClassicHybrid) {
        ClassicHybridBottomChrome(
            mood = mood,
            faceKind = faceKind,
            transcript = transcript,
            speaker = speaker,
            modifier = modifier,
            gazeX = gazeX,
            gazeY = gazeY,
            mouthAmplitude = mouthAmplitude,
            brandGlow = brandGlow,
            highContrast = highContrast,
            gesture = gesture,
            contextGlyph = contextGlyph,
            floatContextGlyph = floatContextGlyph,
            showFace = showFace,
            faceRise = faceRise,
            faceScale = faceScale,
            faceAlpha = faceAlpha,
            transcriptAlpha = transcriptAlpha,
            faceSizeScale = faceSizeScale,
            faceCues = faceCues,
            faceContent = faceContent,
            glowBreath = glowBreath,
        )
        return
    }

    IslandCapsuleBottomChrome(
        mood = mood,
        faceKind = faceKind,
        transcript = transcript,
        speaker = speaker,
        modifier = modifier,
        gazeX = gazeX,
        gazeY = gazeY,
        mouthAmplitude = mouthAmplitude,
        brandGlow = brandGlow,
        highContrast = highContrast,
        gesture = gesture,
        contextGlyph = contextGlyph,
        floatContextGlyph = floatContextGlyph,
        showFace = showFace,
        faceRise = faceRise,
        faceScale = faceScale,
        faceAlpha = faceAlpha,
        transcriptAlpha = transcriptAlpha,
        faceSizeScale = faceSizeScale,
        faceCues = faceCues,
        faceContent = faceContent,
        glowBreath = glowBreath,
    )
}

/**
 * Master-style chrome: full SemiCircle hybrid face on [FaceStageDock],
 * transcript below — no Dynamic Island capsule.
 */
@Composable
private fun ClassicHybridBottomChrome(
    mood: AssistantMood,
    faceKind: AssistantFaceKind,
    transcript: String,
    speaker: DialogueSpeaker,
    modifier: Modifier,
    gazeX: Float?,
    gazeY: Float?,
    mouthAmplitude: Float?,
    brandGlow: Color,
    highContrast: Boolean,
    gesture: FaceGesture,
    contextGlyph: AssistantContextGlyph?,
    floatContextGlyph: Boolean,
    showFace: Boolean,
    faceRise: Float,
    faceScale: Float,
    faceAlpha: Float,
    transcriptAlpha: Float,
    faceSizeScale: Float,
    faceCues: AssistantFaceCues?,
    faceContent: (@Composable (faceModifier: Modifier, faceSize: Dp) -> Unit)?,
    glowBreath: ImmersiveGlowBreath,
) {
    val showGlyph = floatContextGlyph &&
        faceKind == AssistantFaceKind.FusionEyes &&
        contextGlyph != null &&
        faceContent == null

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
                    if (faceContent != null) {
                        faceContent(Modifier.size(faceSize), faceSize)
                    } else {
                        ConfigurableAssistantFace(
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
            ImmersiveTranscript(
                text = transcript,
                speaker = speaker,
                live = speaker == DialogueSpeaker.User && mood == AssistantMood.Listening,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = transcriptAlpha.coerceIn(0f, 1f) },
            )
        }
    }
}

/** Island capsule path — unchanged for non-classic face kinds. */
@Composable
private fun IslandCapsuleBottomChrome(
    mood: AssistantMood,
    faceKind: AssistantFaceKind,
    transcript: String,
    speaker: DialogueSpeaker,
    modifier: Modifier,
    gazeX: Float?,
    gazeY: Float?,
    mouthAmplitude: Float?,
    brandGlow: Color,
    highContrast: Boolean,
    gesture: FaceGesture,
    contextGlyph: AssistantContextGlyph?,
    floatContextGlyph: Boolean,
    showFace: Boolean,
    faceRise: Float,
    faceScale: Float,
    faceAlpha: Float,
    transcriptAlpha: Float,
    faceSizeScale: Float,
    faceCues: AssistantFaceCues?,
    faceContent: (@Composable (faceModifier: Modifier, faceSize: Dp) -> Unit)?,
    glowBreath: ImmersiveGlowBreath,
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
        val density = LocalDensity.current
        val belowPx = with(density) {
            (maxHeight * AssistantOverlayTokens.FaceBelowTravelFraction).toPx()
        }
        val entranceY = when {
            faceRise >= 0f -> 0
            else -> (-faceRise * belowPx).roundToInt()
        }
        val dockAlpha = maxOf(faceAlpha, transcriptAlpha).coerceIn(0f, 1f)

        IslandCapsuleDock(
            mood = mood,
            hasTranscript = hasTranscript,
            transcriptCharCount = transcript.length,
            hasStatusCue = faceCues?.islandStatusIcon() != null,
            contentAlpha = dockAlpha,
            glowBreath = glowBreath,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = AssistantOverlayTokens.IslandBottomInset)
                .offset { IntOffset(0, entranceY) }
                .graphicsLayer {
                    val s = faceScale.coerceIn(0f, 1.15f)
                    scaleX = s
                    scaleY = s
                    alpha = 1f
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* keep session alive */ },
                ),
            face = { baseFaceSize ->
                val faceSize = (baseFaceSize * faceSizeScale).coerceAtLeast(32.dp)
                val glyphSize = (faceSize * AssistantOverlayTokens.GlyphSizeFraction)
                    .coerceIn(AssistantOverlayTokens.GlyphSizeMin, AssistantOverlayTokens.GlyphSizeMax)
                if (showFace && faceKind != AssistantFaceKind.None) {
                    val statusIcon = faceCues?.islandStatusIcon()
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (showGlyph) {
                            AssistantContextGlyphIcon(
                                glyph = contextGlyph,
                                size = glyphSize,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = -(glyphSize * 0.55f)),
                            )
                        }
                        if (faceContent != null) {
                            faceContent(Modifier.fillMaxSize(), faceSize)
                        } else {
                            ConfigurableAssistantFace(
                                mood = mood,
                                kind = faceKind,
                                modifier = Modifier.fillMaxSize(),
                                gazeX = gazeX,
                                gazeY = gazeY,
                                mouthAmplitude = mouthAmplitude,
                                brandGlow = brandGlow,
                                highContrast = highContrast,
                                gesture = gesture,
                                faceCues = faceCues,
                                showShell = false,
                            )
                        }
                        if (statusIcon != null) {
                            IslandStatusCueBadge(
                                icon = statusIcon,
                                highContrast = highContrast,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = AssistantOverlayTokens.IslandCueBadgeMargin),
                            )
                        }
                    }
                }
            },
            transcript = if (hasTranscript) {
                {
                    ImmersiveTranscript(
                        text = transcript,
                        speaker = speaker,
                        live = speaker == DialogueSpeaker.User && mood == AssistantMood.Listening,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = transcriptAlpha.coerceIn(0f, 1f) },
                    )
                }
            } else {
                null
            },
        )
    }
}

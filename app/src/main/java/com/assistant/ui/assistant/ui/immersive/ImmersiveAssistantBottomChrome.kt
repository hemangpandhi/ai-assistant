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
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.ui.chrome.FaceGesture
import com.assistant.ui.assistant.ui.theme.AssistantOverlayTokens
import com.assistant.ui.assistant.ui.theme.AssistantTokens

/**
 * Shared bottom chrome for the immersive assistant stage:
 * Dynamic Island capsule + face (optional floating context glyph) +
 * transcript inside the pill when expanded.
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
    /** Multiplier on island face size (e.g. 1.05f for Weather sink). */
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
        val density = LocalDensity.current
        // Travel distance for bottom → settled entrance (-1 → 0).
        val belowPx = with(density) {
            (maxHeight * AssistantOverlayTokens.FaceBelowTravelFraction).toPx()
        }
        val entranceY = when {
            faceRise >= 0f -> 0
            else -> (-faceRise * belowPx).roundToInt() // faceRise=-1 → push below
        }
        val dockAlpha = maxOf(faceAlpha, transcriptAlpha).coerceIn(0f, 1f)

        // Only the island capsule consumes taps; empty stage passes through
        // to the backdrop dismiss target underneath.
        IslandCapsuleDock(
            mood = mood,
            hasTranscript = hasTranscript,
            transcriptCharCount = transcript.length,
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

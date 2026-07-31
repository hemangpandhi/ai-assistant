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
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.ui.chrome.FaceGesture

/**
 * Pull the face toward the transcript. The face canvas keeps chin / glow clearance
 * below the drawn shell, which otherwise reads as a large gap above the text.
 */
private val FaceTowardTranscriptNudge = 58.dp

/**
 * Shared bottom chrome for the immersive assistant stage:
 * local [FaceStageDock] glass plate + face (optional floating context glyph) +
 * [ImmersiveTranscript].
 *
 * [faceRise] entrance lift: -1 = off-screen below, +1 = stage center, 0 = settled.
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
    brandGlow: Color = Color(0xFF8AB4F8),
    highContrast: Boolean = false,
    gesture: FaceGesture = FaceGesture.None,
    contextGlyph: AssistantContextGlyph? = null,
    floatContextGlyph: Boolean = true,
    showFace: Boolean = true,
    faceRise: Float = 0f,
    faceScale: Float = 1f,
    faceAlpha: Float = 1f,
    transcriptAlpha: Float = 1f,
    /** Multiplier on the ~37.5%-of-stage face size (e.g. 1.05f for Weather sink). */
    faceSizeScale: Float = 1f,
    faceCues: AssistantFaceCues? = null,
    faceContent: (@Composable (faceModifier: Modifier, faceSize: Dp) -> Unit)? = null,
    glowBreath: ImmersiveGlowBreath = ImmersiveGlowBreath(1f, 0f, 0.62f),
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
            .padding(start = 32.dp, top = 16.dp, end = 32.dp, bottom = 0.dp),
    ) {
        // Face targets ~37.5% of the immersive stage (app area) height (1.5× prior 25%).
        val faceSize = (maxHeight * 0.375f * faceSizeScale).coerceIn(88.dp, 480.dp)
        val glyphSize = (faceSize * 0.38f).coerceIn(40.dp, 96.dp)
        val density = LocalDensity.current
        // Travel distances for bottom → center → settle entrance.
        val belowPx = with(density) { (maxHeight * 0.38f).toPx() }
        // Peak lift — keep below true stage center so the arc doesn't overshoot.
        val centerPx = with(density) { (maxHeight * 0.26f).toPx() }
        val entranceY = when {
            faceRise >= 0f -> -faceRise * centerPx // up toward center
            else -> -faceRise * belowPx // faceRise=-1 → push below
        }.roundToInt()
        // Diameter ≥ ~2× face+transcript stack so the semicircle's 0% rim clears
        // the top of the chrome (avoids left/right clip → hard edges).
        val estimatedStack = faceSize + 100.dp
        val dockWidth = maxOf(faceSize * 2.5f, estimatedStack * 2.2f)
            .coerceIn(320.dp, maxWidth)
        val dockAlpha = maxOf(faceAlpha, transcriptAlpha).coerceIn(0f, 1f)

        // Only the face + transcript dock consumes taps; empty stage passes through
        // to the backdrop dismiss target underneath.
        FaceStageDock(
            brandGlow = brandGlow,
            width = dockWidth,
            contentAlpha = dockAlpha,
            glowBreath = glowBreath,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .offset { IntOffset(0, entranceY) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* keep session alive */ },
                ),
        ) {
            if (showFace && faceKind != AssistantFaceKind.None) {
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .padding(top = if (showGlyph) glyphSize * 0.72f else 0.dp)
                        .offset {
                            IntOffset(0, FaceTowardTranscriptNudge.roundToPx())
                        }
                        .graphicsLayer {
                            val s = faceScale
                            scaleX = s
                            scaleY = s
                            // Dock owns fade; keep face fully painted inside the plate.
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
                        )
                    }
                }
            }
            ImmersiveTranscript(
                text = transcript,
                speaker = speaker,
                live = speaker == DialogueSpeaker.User && mood == AssistantMood.Listening,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

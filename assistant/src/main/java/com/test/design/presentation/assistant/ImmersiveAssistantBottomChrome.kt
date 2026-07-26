package com.test.design.presentation.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.test.design.assistant.api.AssistantContextGlyph
import kotlin.math.roundToInt

/**
 * Shared bottom chrome for the immersive assistant stage:
 * face (optional floating context glyph) + [ImmersiveTranscript].
 *
 * [faceContent] replaces [ConfigurableAssistantFace] when provided (e.g. Weather sink).
 * [floatContextGlyph] shows the Material icon above Fusion Eyes; Weather sink keeps this off
 * and swaps the icon into the eye band instead.
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
    /** Multiplier on the computed face size (e.g. 1.05f for Weather sink). */
    faceSizeScale: Float = 1f,
    faceContent: (@Composable (faceModifier: Modifier, faceSize: Dp) -> Unit)? = null,
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
        val bandHeight = maxHeight * 0.25f
        val faceSize = (bandHeight * 0.64f * faceSizeScale).coerceIn(88.dp, 200.dp)
        val glyphSize = (faceSize * 0.38f).coerceIn(40.dp, 56.dp)
        val density = LocalDensity.current
        val risePx = with(density) { (bandHeight * 0.95f).toPx() }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (showFace && faceKind != AssistantFaceKind.None) {
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .padding(top = if (showGlyph) glyphSize * 0.72f else 0.dp)
                        .offset {
                            IntOffset(0, (faceRise * risePx).roundToInt())
                        }
                        .graphicsLayer {
                            val s = faceScale
                            scaleX = s
                            scaleY = s
                            alpha = faceAlpha.coerceIn(0f, 1f)
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
                    .graphicsLayer { alpha = transcriptAlpha.coerceIn(0f, 1f) }
                    .padding(top = 8.dp),
            )
        }
    }
}

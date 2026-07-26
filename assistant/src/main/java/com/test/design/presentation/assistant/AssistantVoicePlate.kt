package com.test.design.presentation.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelShape = RoundedCornerShape(AssistantTokens.PanelCorner)

private val PromptCrossfade = fadeIn(
    tween(AssistantTokens.CrossfadeMs, easing = FastOutSlowInEasing),
) togetherWith fadeOut(
    tween(AssistantTokens.CrossfadeMs - 80, easing = FastOutSlowInEasing),
)

/**
 * Production assistant panel — Scalable UI trailing overlay with soft blue glow.
 */
@Composable
fun AssistantSidePanel(
    mood: AssistantMood,
    onMoodChange: (AssistantMood) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    autoPlay: Boolean = true,
) {
    val playback = rememberDialoguePlayback(autoPlay = autoPlay)
    DialoguePlaybackEffects(
        playback = playback,
        onMoodChange = onMoodChange,
    )
    val beat = playback.activeBeat

    Surface(
        modifier = modifier
            .width(AssistantTokens.PanelWidth)
            .fillMaxHeight()
            .shadow(
                elevation = 20.dp,
                shape = PanelShape,
                clip = false,
                ambientColor = AssistantTokens.PanelGlow,
                spotColor = AssistantTokens.PanelGlowSoft,
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        AssistantTokens.PanelGlowEdge,
                        AssistantTokens.Hairline,
                        AssistantTokens.PanelGlowEdge.copy(alpha = 0.35f),
                    ),
                ),
                shape = PanelShape,
            ),
        shape = PanelShape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(PanelShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AssistantTokens.Surface)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AssistantTokens.SurfaceTop.copy(alpha = 0.55f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // Soft inner blue wash near the rim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.5.dp,
                        color = AssistantTokens.PanelGlowEdge.copy(alpha = 0.22f),
                        shape = PanelShape,
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AssistantTokens.ContentPadding),
            ) {
                PanelHeader(
                    playing = playback.playing,
                    onPlayPause = playback::playPause,
                    onReplay = playback::replay,
                    onDismiss = onDismiss,
                )

                Spacer(Modifier.height(20.dp))

                // Persona hero — ambient aura behind eyes + mouth character
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    PersonaStage(mood = mood)
                }

                Spacer(Modifier.height(8.dp))

                AnimatedContent(
                    targetState = beat,
                    transitionSpec = { PromptCrossfade },
                    label = "prompt",
                    modifier = Modifier.fillMaxWidth(),
                ) { current ->
                    PanelInlinePrompt(
                        beat = current,
                        userPrompt = playback.latestUserPrompt,
                    )
                }
            }
        }
    }
}

/**
 * Center stage: abstract glowing persona over a cool mood-reactive waveform.
 */
@Composable
private fun PersonaStage(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            AssistantPresence(
                mood = mood,
                modifier = Modifier.fillMaxSize(),
            )
            AssistantFace(
                mood = mood,
                modifier = Modifier.size(184.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        VoiceWaveform(
            mood = mood,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun PanelHeader(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onReplay: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "In-car assistant",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp,
                ),
                color = AssistantTokens.OnSurface,
            )
        }

        val quietColors = IconButtonDefaults.iconButtonColors(
            contentColor = AssistantTokens.OnSurfaceVariant,
        )
        IconButton(onClick = onPlayPause, colors = quietColors) {
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
            )
        }
        IconButton(onClick = onReplay, colors = quietColors) {
            Icon(Icons.Default.Replay, contentDescription = "Replay")
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, colors = quietColors) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
    }
}

@Composable
private fun PanelInlinePrompt(
    beat: DialogueBeat,
    userPrompt: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (beat.speaker) {
            DialogueSpeaker.User -> {
                Text(
                    text = beat.text,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Normal,
                        lineHeight = 32.sp,
                    ),
                    color = AssistantTokens.OnSurface,
                    textAlign = TextAlign.Center,
                )
            }
            DialogueSpeaker.Assistant -> {
                if (!userPrompt.isNullOrBlank()) {
                    Text(
                        text = userPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AssistantTokens.OnSurfaceMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
                Text(
                    text = beat.text,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Normal,
                        lineHeight = 32.sp,
                    ),
                    color = AssistantTokens.OnSurface,
                    textAlign = TextAlign.Center,
                )
            }
            DialogueSpeaker.System -> {
                Text(
                    text = beat.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AssistantTokens.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Shared mood label for chips + captions. */
val AssistantMood.voiceLabel: String
    get() = label

/** Back-compat alias. */
@Composable
fun AssistantVoicePlate(
    mood: AssistantMood,
    onMoodChange: (AssistantMood) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    autoPlay: Boolean = true,
) {
    AssistantSidePanel(
        mood = mood,
        onMoodChange = onMoodChange,
        modifier = modifier,
        onDismiss = onDismiss,
        autoPlay = autoPlay,
    )
}

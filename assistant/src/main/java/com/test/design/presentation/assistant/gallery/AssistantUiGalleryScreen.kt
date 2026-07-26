package com.test.design.presentation.assistant.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.test.design.presentation.assistant.AssistantMood
import com.test.design.presentation.assistant.ImmersiveBorderGlow
import kotlinx.coroutines.delay

private val GalleryMoodCycle = listOf(
    AssistantMood.Idle,
    AssistantMood.Listening,
    AssistantMood.Speaking,
    AssistantMood.Thinking,
    AssistantMood.Happy,
    AssistantMood.Sad,
    AssistantMood.Excited,
    AssistantMood.Bored,
    AssistantMood.Drowsy,
    AssistantMood.Tired,
    AssistantMood.Searching,
)

/** Fully opaque stage behind every gallery chrome style. */
private val GalleryStageTop = Color(0xFF1A1C22)
private val GalleryStageBottom = Color(0xFF0B0C10)

/**
 * Assistant UI gallery — pick a chrome style and mood.
 * Stage background is always opaque; moods auto-advance every 2 seconds.
 */
@Composable
fun AssistantUiGalleryScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialStyle: AssistantUiStyle = AssistantUiStyle.VoicePlate,
) {
    var style by rememberSaveable { mutableStateOf(initialStyle) }
    var mood by rememberSaveable { mutableStateOf(AssistantMood.Listening) }

    LaunchedEffect(mood) {
        delay(2_000)
        val index = GalleryMoodCycle.indexOf(mood).coerceAtLeast(0)
        mood = GalleryMoodCycle[(index + 1) % GalleryMoodCycle.size]
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(GalleryStageTop, GalleryStageBottom),
                    ),
                ),
        )
        ImmersiveBorderGlow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Live preview fills the stage
            AssistantUiVariant(
                style = style,
                mood = mood,
                modifier = Modifier.fillMaxSize(),
                prompt = moodPrompt(mood),
            )

            // Top chrome: style picker
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = style.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = AssistantUiChrome.OnGlass,
                        )
                        Text(
                            text = style.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = AssistantUiChrome.OnGlassMuted,
                        )
                    }
                    Text(
                        text = "Close",
                        color = AssistantUiChrome.Accent,
                        modifier = Modifier
                            .clickable(onClick = onClose)
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistantUiStyle.entries.forEach { entry ->
                        FilterChip(
                            selected = style == entry,
                            onClick = { style = entry },
                            label = { Text(entry.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = AssistantUiChrome.Glass,
                                labelColor = AssistantUiChrome.OnGlassMuted,
                                selectedContainerColor = AssistantUiChrome.Accent.copy(alpha = 0.28f),
                                selectedLabelColor = AssistantUiChrome.OnGlass,
                            ),
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GalleryMoodCycle.forEach { entry ->
                        FilterChip(
                            selected = mood == entry,
                            onClick = { mood = entry },
                            label = { Text(entry.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = AssistantUiChrome.Glass,
                                labelColor = AssistantUiChrome.OnGlassMuted,
                                selectedContainerColor = entry.glowColor.copy(alpha = 0.28f),
                                selectedLabelColor = AssistantUiChrome.OnGlass,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun moodPrompt(mood: AssistantMood): String = when (mood) {
    AssistantMood.Idle -> "Ready when you are"
    AssistantMood.Listening -> "Listening…"
    AssistantMood.Speaking -> "Here's what I found"
    AssistantMood.Thinking -> "Thinking…"
    AssistantMood.Happy -> "Glad I could help"
    AssistantMood.Sad -> "Sorry about that"
    AssistantMood.Excited -> "Great news!"
    AssistantMood.Bored -> "Anything else?"
    AssistantMood.Drowsy -> "Getting quiet…"
    AssistantMood.Tired -> "Still here for you"
    AssistantMood.Reading -> "Scanning…"
    AssistantMood.Searching -> "Looking that up"
}

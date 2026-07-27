package com.test.design.assistant.ui.chrome

import com.test.design.assistant.face.AssistantMood

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
/** Legacy entry — routes to the side panel experience. */
@Composable
fun AssistantDialogueStage(
    mood: AssistantMood,
    onMoodChange: (AssistantMood) -> Unit,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
) {
    AssistantSidePanel(
        mood = mood,
        onMoodChange = onMoodChange,
        autoPlay = autoPlay,
        modifier = modifier.fillMaxHeight(),
    )
}

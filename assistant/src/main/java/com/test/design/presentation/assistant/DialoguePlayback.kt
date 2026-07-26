package com.test.design.presentation.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Shared playback controller for the simulated dialogue script.
 */
class DialoguePlaybackState internal constructor(
    autoPlay: Boolean,
) {
    var beatIndex by mutableIntStateOf(0)
        internal set
    var playing by mutableStateOf(autoPlay)
        internal set

    val activeBeat: DialogueBeat
        get() = DemoDialogueScript.getOrNull(beatIndex.coerceIn(0, DemoDialogueScript.lastIndex))
            ?: DemoDialogueScript.first()

    /** Most recent user line (for showing the prompt in the plate while the assistant replies). */
    val latestUserPrompt: String?
        get() = DemoDialogueScript
            .take(beatIndex + 1)
            .lastOrNull { it.speaker == DialogueSpeaker.User }
            ?.text

    fun playPause() {
        if (!playing && beatIndex >= DemoDialogueScript.lastIndex) {
            beatIndex = 0
        }
        playing = !playing
    }

    fun replay() {
        beatIndex = 0
        playing = true
    }
}

@Composable
fun rememberDialoguePlayback(autoPlay: Boolean = true): DialoguePlaybackState {
    return remember { DialoguePlaybackState(autoPlay = autoPlay) }
}

@Composable
fun DialoguePlaybackEffects(
    playback: DialoguePlaybackState,
    onMoodChange: (AssistantMood) -> Unit,
) {
    val context = LocalContext.current
    val wake = rememberAssistantWakeFeedback()
    LaunchedEffect(playback.beatIndex, playback.playing) {
        if (!playback.playing) return@LaunchedEffect
        val beat = DemoDialogueScript.getOrNull(playback.beatIndex) ?: return@LaunchedEffect
        if (playback.beatIndex == 0) {
            wake.play()
        }
        onMoodChange(beat.mood)
        if (shouldSpeakBeat(beat)) {
            speakAssistantUtterance(context, beat.text, beat.holdMs)
        } else {
            delay(beat.holdMs)
        }
        if (playback.beatIndex < DemoDialogueScript.lastIndex) {
            playback.beatIndex += 1
        } else {
            playback.playing = false
            wake.playDismiss()
        }
    }
}

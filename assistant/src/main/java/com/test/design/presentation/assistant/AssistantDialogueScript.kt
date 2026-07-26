package com.test.design.presentation.assistant

import com.test.design.assistant.api.AssistantContextGlyph

/**
 * One beat in the simulated user ↔ assistant conversation.
 * [mood] drives the side persona while the line is active.
 * [contextGlyph] optionally shows a weather / climate glance icon (Fusion Eyes).
 */
data class DialogueBeat(
    val speaker: DialogueSpeaker,
    val text: String,
    val mood: AssistantMood,
    val holdMs: Long = 2200L,
    val contextGlyph: AssistantContextGlyph? = null,
)

enum class DialogueSpeaker { User, Assistant, System }

/**
 * Demo script that walks through every personality state while chatting.
 */
val DemoDialogueScript: List<DialogueBeat> = listOf(
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Assistant ready",
        mood = AssistantMood.Idle,
        holdMs = 1400,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Hey — can you help me plan a stop for coffee?",
        mood = AssistantMood.Listening,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Of course! Let me think about what's nearby…",
        mood = AssistantMood.Thinking,
        holdMs = 2400,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Searching cafés along your route…",
        mood = AssistantMood.Searching,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Reading reviews for Bluebird Roasters — highly rated, 6 min away.",
        mood = AssistantMood.Reading,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "I found Bluebird Roasters. Want me to add it as a stop?",
        mood = AssistantMood.Speaking,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Yes please!",
        mood = AssistantMood.Listening,
        holdMs = 1800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Done — stop added. You’re going to love their cold brew!",
        mood = AssistantMood.Happy,
        holdMs = 2800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Actually… wait, are they closed today?",
        mood = AssistantMood.Listening,
        holdMs = 2200,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Checking hours…",
        mood = AssistantMood.Searching,
        holdMs = 2000,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Oh no — they close in 10 minutes. Sorry about that.",
        mood = AssistantMood.Sad,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Harbor Light Café is open until 10 — exciting backup!",
        mood = AssistantMood.Excited,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Quiet highway stretch…",
        mood = AssistantMood.Bored,
        holdMs = 1800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Late night mode",
        mood = AssistantMood.Drowsy,
        holdMs = 1800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "I'll keep watch while you drive. Rest when you can.",
        mood = AssistantMood.Tired,
        holdMs = 2600,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Want me to add Harbor Light as a stop?",
        mood = AssistantMood.Speaking,
        holdMs = 2400,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.User,
        text = "Sure, surprise me.",
        mood = AssistantMood.Listening,
        holdMs = 1800,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.Assistant,
        text = "Harbor Light Café is set. Safe travels!",
        mood = AssistantMood.Happy,
        holdMs = 3000,
    ),
    DialogueBeat(
        speaker = DialogueSpeaker.System,
        text = "Simulation complete — tap Replay to watch again",
        mood = AssistantMood.Idle,
        holdMs = 2000,
    ),
)

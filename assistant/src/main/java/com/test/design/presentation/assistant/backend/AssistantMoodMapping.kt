package com.test.design.presentation.assistant.backend

import com.test.design.assistant.api.AssistantGesture
import com.test.design.assistant.api.AssistantMoodId
import com.test.design.assistant.api.AssistantSpeaker
import com.test.design.presentation.assistant.AssistantMood
import com.test.design.presentation.assistant.DialogueSpeaker
import com.test.design.presentation.assistant.FaceGesture

internal fun AssistantMood.toMoodId(): AssistantMoodId = when (this) {
    AssistantMood.Idle -> AssistantMoodId.Idle
    AssistantMood.Listening -> AssistantMoodId.Listening
    AssistantMood.Speaking -> AssistantMoodId.Speaking
    AssistantMood.Thinking -> AssistantMoodId.Thinking
    AssistantMood.Happy -> AssistantMoodId.Happy
    AssistantMood.Sad -> AssistantMoodId.Sad
    AssistantMood.Excited -> AssistantMoodId.Excited
    AssistantMood.Bored -> AssistantMoodId.Bored
    AssistantMood.Drowsy -> AssistantMoodId.Drowsy
    AssistantMood.Tired -> AssistantMoodId.Tired
    AssistantMood.Reading -> AssistantMoodId.Reading
    AssistantMood.Searching -> AssistantMoodId.Searching
}

internal fun AssistantMoodId.toUiMood(): AssistantMood = when (this) {
    AssistantMoodId.Idle -> AssistantMood.Idle
    AssistantMoodId.Listening -> AssistantMood.Listening
    AssistantMoodId.Speaking -> AssistantMood.Speaking
    AssistantMoodId.Thinking -> AssistantMood.Thinking
    AssistantMoodId.Happy -> AssistantMood.Happy
    AssistantMoodId.Sad -> AssistantMood.Sad
    AssistantMoodId.Excited -> AssistantMood.Excited
    AssistantMoodId.Bored -> AssistantMood.Bored
    AssistantMoodId.Drowsy -> AssistantMood.Drowsy
    AssistantMoodId.Tired -> AssistantMood.Tired
    AssistantMoodId.Reading -> AssistantMood.Reading
    AssistantMoodId.Searching -> AssistantMood.Searching
}

internal fun AssistantSpeaker.toUiSpeaker(): DialogueSpeaker = when (this) {
    AssistantSpeaker.User -> DialogueSpeaker.User
    AssistantSpeaker.Assistant -> DialogueSpeaker.Assistant
    AssistantSpeaker.System -> DialogueSpeaker.System
}

internal fun DialogueSpeaker.toApiSpeaker(): AssistantSpeaker = when (this) {
    DialogueSpeaker.User -> AssistantSpeaker.User
    DialogueSpeaker.Assistant -> AssistantSpeaker.Assistant
    DialogueSpeaker.System -> AssistantSpeaker.System
}

internal fun AssistantGesture.toUiGesture(): FaceGesture = when (this) {
    AssistantGesture.None -> FaceGesture.None
    AssistantGesture.Nod -> FaceGesture.Nod
    AssistantGesture.Shake -> FaceGesture.Shake
}

internal fun FaceGesture.toApiGesture(): AssistantGesture = when (this) {
    FaceGesture.None -> AssistantGesture.None
    FaceGesture.Nod -> AssistantGesture.Nod
    FaceGesture.Shake -> AssistantGesture.Shake
}

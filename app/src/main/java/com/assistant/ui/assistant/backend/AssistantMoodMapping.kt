package com.assistant.ui.assistant.backend

import com.assistant.ui.assistant.api.AssistantGesture
import com.assistant.ui.assistant.api.AssistantMoodId
import com.assistant.ui.assistant.api.AssistantSpeaker
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.dialogue.DialogueSpeaker
import com.assistant.ui.assistant.ui.chrome.FaceGesture

internal fun AssistantMood.toMoodId(): AssistantMoodId = when (this) {
    AssistantMood.Idle -> AssistantMoodId.Idle
    AssistantMood.Listening -> AssistantMoodId.Listening
    AssistantMood.Speaking -> AssistantMoodId.Speaking
    AssistantMood.Thinking -> AssistantMoodId.Thinking
    AssistantMood.Reading -> AssistantMoodId.Reading
    AssistantMood.Searching -> AssistantMoodId.Searching
    AssistantMood.Attraction -> AssistantMoodId.Attraction
    AssistantMood.Admiration -> AssistantMoodId.Admiration
    AssistantMood.Desire -> AssistantMoodId.Desire
    AssistantMood.Interest -> AssistantMoodId.Interest
    AssistantMood.Surprise -> AssistantMoodId.Surprise
    AssistantMood.Astonishment -> AssistantMoodId.Astonishment
    AssistantMood.Happy -> AssistantMoodId.Happy
    AssistantMood.Amused -> AssistantMoodId.Amused
    AssistantMood.Joyous -> AssistantMoodId.Joyous
    AssistantMood.Excited -> AssistantMoodId.Excited
    AssistantMood.Jubilation -> AssistantMoodId.Jubilation
    AssistantMood.Gratitude -> AssistantMoodId.Gratitude
    AssistantMood.Contentment -> AssistantMoodId.Contentment
    AssistantMood.Proud -> AssistantMoodId.Proud
    AssistantMood.Triumph -> AssistantMoodId.Triumph
    AssistantMood.Relaxed -> AssistantMoodId.Relaxed
    AssistantMood.Shy -> AssistantMoodId.Shy
    AssistantMood.Acceptance -> AssistantMoodId.Acceptance
    AssistantMood.Complicity -> AssistantMoodId.Complicity
    AssistantMood.Concentration -> AssistantMoodId.Concentration
    AssistantMood.Dreamy -> AssistantMoodId.Dreamy
    AssistantMood.Drowsy -> AssistantMoodId.Drowsy
    AssistantMood.Tired -> AssistantMoodId.Tired
    AssistantMood.Sleeping -> AssistantMoodId.Sleeping
    AssistantMood.Doubt -> AssistantMoodId.Doubt
    AssistantMood.Concerned -> AssistantMoodId.Concerned
    AssistantMood.Impressed -> AssistantMoodId.Impressed
    AssistantMood.Sad -> AssistantMoodId.Sad
    AssistantMood.Bored -> AssistantMoodId.Bored
}

internal fun AssistantMoodId.toUiMood(): AssistantMood = when (this) {
    AssistantMoodId.Idle -> AssistantMood.Idle
    AssistantMoodId.Listening -> AssistantMood.Listening
    AssistantMoodId.Speaking -> AssistantMood.Speaking
    AssistantMoodId.Thinking -> AssistantMood.Thinking
    AssistantMoodId.Reading -> AssistantMood.Reading
    AssistantMoodId.Searching -> AssistantMood.Searching
    AssistantMoodId.Attraction -> AssistantMood.Attraction
    AssistantMoodId.Admiration -> AssistantMood.Admiration
    AssistantMoodId.Desire -> AssistantMood.Desire
    AssistantMoodId.Interest -> AssistantMood.Interest
    AssistantMoodId.Surprise -> AssistantMood.Surprise
    AssistantMoodId.Astonishment -> AssistantMood.Astonishment
    AssistantMoodId.Happy -> AssistantMood.Happy
    AssistantMoodId.Amused -> AssistantMood.Amused
    AssistantMoodId.Joyous -> AssistantMood.Joyous
    AssistantMoodId.Excited -> AssistantMood.Excited
    AssistantMoodId.Jubilation -> AssistantMood.Jubilation
    AssistantMoodId.Gratitude -> AssistantMood.Gratitude
    AssistantMoodId.Contentment -> AssistantMood.Contentment
    AssistantMoodId.Proud -> AssistantMood.Proud
    AssistantMoodId.Triumph -> AssistantMood.Triumph
    AssistantMoodId.Relaxed -> AssistantMood.Relaxed
    AssistantMoodId.Shy -> AssistantMood.Shy
    AssistantMoodId.Acceptance -> AssistantMood.Acceptance
    AssistantMoodId.Complicity -> AssistantMood.Complicity
    AssistantMoodId.Concentration -> AssistantMood.Concentration
    AssistantMoodId.Dreamy -> AssistantMood.Dreamy
    AssistantMoodId.Drowsy -> AssistantMood.Drowsy
    AssistantMoodId.Tired -> AssistantMood.Tired
    AssistantMoodId.Sleeping -> AssistantMood.Sleeping
    AssistantMoodId.Doubt -> AssistantMood.Doubt
    AssistantMoodId.Concerned -> AssistantMood.Concerned
    AssistantMoodId.Impressed -> AssistantMood.Impressed
    AssistantMoodId.Sad -> AssistantMood.Sad
    AssistantMoodId.Bored -> AssistantMood.Bored
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

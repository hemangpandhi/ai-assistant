package com.test.design.presentation.assistant.backend

import android.content.Context
import com.test.design.assistant.api.AssistantTtsEngine
import com.test.design.presentation.assistant.assistantUtteranceLipSync
import kotlinx.coroutines.flow.Flow

/** Host TTS + lip-sync via Android [android.speech.tts.TextToSpeech]. */
fun platformAssistantTts(context: Context): AssistantTtsEngine {
    val app = context.applicationContext
    return AssistantTtsEngine { text, holdMs ->
        assistantUtteranceLipSync(app, text, holdMs)
    }
}

private fun AssistantTtsEngine(
    block: (text: String, holdMs: Long) -> Flow<Float>,
): AssistantTtsEngine = object : AssistantTtsEngine {
    override fun speak(text: String, holdMs: Long): Flow<Float> = block(text, holdMs)
}

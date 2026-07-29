package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class PassengerEntertainmentUseCase : IProactiveUseCase {
    override val name = "Passenger Entertainment"
    override val priority = PriorityLevel.ENTERTAINMENT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 60_000L // 1 minute

    override fun evaluate(context: CabinContext): TriggerResult? {
        val mood = context.gestureFeedback?.mood ?: return null
        val now = context.timestamp

        if (mood.contains("Sad", ignoreCase = true)) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.w("VisionOrchestrator", "Sad passenger detected in backseat!")
                val prompt = "[SYSTEM EVENT: A passenger in the back seat seems sad or restless. Proactively whisper/ask the driver: 'The passenger in the back seems restless. Should I start an audiobook or play a trivia game to keep them entertained?']"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
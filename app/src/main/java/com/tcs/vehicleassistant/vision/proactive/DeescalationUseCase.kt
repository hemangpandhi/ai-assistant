package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class DeescalationUseCase : IProactiveUseCase {
    override val name = "Cabin Deescalation"
    override val priority = PriorityLevel.COMFORT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 300_000L // 5 minutes

    override fun evaluate(context: CabinContext): TriggerResult? {
        val feedback = context.gestureFeedback ?: return null
        val mood = feedback.mood
        val passengerDetected = feedback.passengerDetected
        val now = context.timestamp

        if ((mood.contains("Angry", ignoreCase = true) || mood.contains("Frustrated", ignoreCase = true)) && passengerDetected) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.d("VisionOrchestrator", "Angry mood + Passenger detected = Tension!")
                val prompt = "[SYSTEM EVENT: An angry/frustrated mood was detected in the cabin while a passenger is present. Act as a friendly mediator to defuse tension. Proactively say EXACTLY: 'It seems a bit tense in the cabin today. How about we lighten the mood? I can play some relaxing music or start a fun trivia game if you'd like?']"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
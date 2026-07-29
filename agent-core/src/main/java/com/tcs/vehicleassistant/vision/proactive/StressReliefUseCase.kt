package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class StressReliefUseCase : IProactiveUseCase {
    override val name = "Stress Relief Ambiance"
    override val priority = PriorityLevel.COMFORT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 60_000L // 1 minute

    override fun evaluate(context: CabinContext): TriggerResult? {
        val mood = context.gestureFeedback?.mood ?: return null
        val now = context.timestamp

        if (context.healthState.stressLevel == "High" && 
            (mood.contains("Angry", ignoreCase = true) || mood.contains("Frustrated", ignoreCase = true))) {
            
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.w("VisionOrchestrator", "High stress and angry mood detected!")
                val prompt = "[SYSTEM EVENT: The driver's biometric stress is High and they look angry. Proactively say EXACTLY: 'You seem a bit stressed. Would you like me to lower the temperature and play some relaxing jazz?' WAIT for the user to say yes before using the setTemperature, setAmbientColor, and playMusic tools.]"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
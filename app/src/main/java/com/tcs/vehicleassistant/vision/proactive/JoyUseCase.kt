package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class JoyUseCase : IProactiveUseCase {
    override val name = "Celebrate Joy"
    override val priority = PriorityLevel.ENTERTAINMENT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 300_000L // 5 minutes

    override fun evaluate(context: CabinContext): TriggerResult? {
        val mood = context.gestureFeedback?.mood ?: return null
        val now = context.timestamp

        if (mood.contains("Happy", ignoreCase = true) || mood.contains("Smile", ignoreCase = true)) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.w("VisionOrchestrator", "Driver is happy!")
                val prompt = "[SYSTEM EVENT: The driver is smiling and looks genuinely happy. Casually chime in with something like: 'Looks like you're really enjoying the drive today! Would you like me to save this route or the current song to your favorites?']"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
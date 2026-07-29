package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class PeaceSignUseCase : IProactiveUseCase {
    override val name = "Peace Sign Scenic Route"
    override val priority = PriorityLevel.ENTERTAINMENT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 600_000L // 10 minutes

    override fun evaluate(context: CabinContext): TriggerResult? {
        val gesture = context.gestureFeedback?.gestureName ?: return null
        val now = context.timestamp

        if (gesture.contains("Victory", ignoreCase = true)) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.d("VisionOrchestrator", "Victory (Peace) sign detected!")
                val prompt = "[SYSTEM EVENT: The driver threw a peace sign (Victory gesture) to the camera. They are feeling chill. Proactively say EXACTLY: 'Peace out! ✌️ It looks like you're taking it easy today. Would you like me to switch our navigation to a scenic route?']"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
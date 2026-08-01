package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class DrowsinessUseCase : IProactiveUseCase {
    override val name = "Drowsiness Alert"
    override val priority = PriorityLevel.CRITICAL_SAFETY
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 120_000L // 2 minutes

    override fun evaluate(context: CabinContext): TriggerResult? {
        val isSleeping = context.gestureFeedback?.isSleeping ?: return null
        val now = context.timestamp

        if (isSleeping) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.e("VisionOrchestrator", "CRITICAL: Driver is falling asleep!")
                val prompt = "[CRITICAL SYSTEM EVENT: The driver appears to be falling asleep at the wheel. Immediately interrupt and warn them loudly. Proactively say EXACTLY: 'Warning, you appear to be falling asleep. Please pull over immediately. Would you like me to navigate to the nearest coffee shop or rest stop?']"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
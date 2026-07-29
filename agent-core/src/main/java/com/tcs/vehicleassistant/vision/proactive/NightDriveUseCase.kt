package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class NightDriveUseCase : IProactiveUseCase {
    override val name = "Night Drive Ambiance"
    override val priority = PriorityLevel.COMFORT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 3600_000L // 1 hour (only trigger once per long night drive)

    override fun evaluate(context: CabinContext): TriggerResult? {
        val isNightMode = context.gestureFeedback?.isNightMode ?: return null
        val now = context.timestamp

        if (isNightMode) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.d("VisionOrchestrator", "Night mode detected in cabin!")
                val prompt = "[SYSTEM EVENT: The vision system detects low light/nighttime conditions. Proactively say EXACTLY: 'I noticed it's getting dark out. Would you like me to dim the ambient lights and switch the climate to a cozy setting for the night drive?']"
                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class PersonalizedWelcomeUseCase : IProactiveUseCase {
    override val name = "Personalized Welcome"
    override val priority = PriorityLevel.COMFORT
    
    private var hasWelcomed = false

    override fun evaluate(context: CabinContext): TriggerResult? {
        val driverName = context.gestureFeedback?.driverName ?: return null

        if (driverName != "Guest" && driverName.isNotBlank() && !hasWelcomed) {
            hasWelcomed = true
            Log.d("VisionOrchestrator", "Known driver detected: $driverName")
            val prompt = "[SYSTEM EVENT: The facial recognition system just identified the driver as $driverName. This is the first time they are seen this session. Proactively say EXACTLY: 'Welcome back, $driverName. I hope you're having a great day. Would you like me to set the temperature to your usual 21 degrees and resume your favorite playlist?']"
            return TriggerResult(prompt, priority)
        }
        return null
    }
}
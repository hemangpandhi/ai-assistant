package com.tcs.vehicleassistant.vision.proactive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class SafetyEmergencyUseCase(private val context: Context) : IProactiveUseCase {
    override val name = "Health Emergency Alert"
    override val priority = PriorityLevel.CRITICAL_SAFETY
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 120_000L // 2 minutes

    override fun evaluate(cabinContext: CabinContext): TriggerResult? {
        val bpm = cabinContext.healthState.heartRate
        val now = cabinContext.timestamp

        // Placeholder for extreme vitals
        if (bpm > 0 && (bpm < 40 || bpm > 140)) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.e("VisionOrchestrator", "CRITICAL: Extreme heart rate detected ($bpm BPM)!")
                val prompt = "[SYSTEM EVENT: URGENT! The driver's heart rate is dangerously abnormal ($bpm BPM). The system has ALREADY routed to the nearest hospital. You DO NOT need to use any navigation tools. Interrupt immediately and say EXACTLY: 'Are you feeling okay? I've noticed a severe anomaly in your vitals. I am routing us to the nearest hospital.']"
                
                // Fire navigation intent to hospital for safety
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=nearest+hospital"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)

                return TriggerResult(prompt, priority)
            }
        }
        return null
    }
}
package com.tcs.vehicleassistant.vision

import android.content.Context
import android.util.Log
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.MediaActionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri

class VisionOrchestratorBridge(private val context: Context, private val orchestrator: AgentOrchestrator) {

    private val mediaBridge = MediaActionBridge(context)
    
    private var lastGestureTrigger = 0L
    private var lastStressTrigger = 0L
    private var lastPassengerTrigger = 0L
    private var lastEmergencyTrigger = 0L
    private var lastJoyTrigger = 0L
    private val DEBOUNCE_MS = 60000L // 1 minute cooldown
    private val GESTURE_DEBOUNCE_MS = 10000L // 10 seconds for gestures

    fun onHealthUpdate(healthState: HealthState, gestureFeedback: GestureFeedback) {
        val now = System.currentTimeMillis()
        val bpm = healthState.heartRate
        val mood = gestureFeedback.mood
        val gesture = gestureFeedback.gestureName

        // Use-Case 1: Context-Aware Gesture Shortcuts (Thumbs Down)
        if (gesture.contains("Thumb_Down", ignoreCase = true)) {
            if (now - lastGestureTrigger > GESTURE_DEBOUNCE_MS) {
                lastGestureTrigger = now
                Log.w("VisionOrchestrator", "Thumbs Down gesture detected!")
                
                if (mediaBridge.isMusicPlaying()) {
                    val prompt = "[SYSTEM EVENT: The driver gave a 'Thumbs Down' gesture while music was playing. Immediately say 'Not a fan? I'll skip to the next song.' Do not wait for confirmation, just do it!]"
                    triggerLLM(prompt)
                    // We also immediately execute the media skip (or let the LLM do it via tools, but here we do it directly for speed)
                    CoroutineScope(Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(2000)
                        mediaBridge.skipNext()
                    }
                } else {
                    val prompt = "[SYSTEM EVENT: The driver gave a 'Thumbs Down' gesture. Ask them what they are dissatisfied with, e.g., 'Not a fan of the route? Should I find an alternative?']"
                    triggerLLM(prompt)
                }
            }
        }

        // Use-Case 2: Proactive Ambiance Mitigation (Stress Relief)
        if (healthState.stressLevel == "High" && (mood.contains("Angry", ignoreCase = true) || mood.contains("Frustrated", ignoreCase = true))) {
            if (now - lastStressTrigger > DEBOUNCE_MS) {
                lastStressTrigger = now
                Log.w("VisionOrchestrator", "High stress and angry mood detected!")
                
                CoroutineScope(Dispatchers.Main).launch {
                    VehicleManager.setTemperature(19f)
                    VehicleManager.setAmbientColor(0, 0, 255) // Cool blue
                }
                
                val prompt = "[SYSTEM EVENT: The driver's biometric stress is High and they look angry. I have automatically lowered the cabin temperature and changed ambient lighting to a calming blue. Proactively say: 'You seem a bit stressed. I've lowered the temperature and set a calming ambient color. Would you like me to play some relaxing jazz?']"
                triggerLLM(prompt)
            }
        }

        // Use-Case 4: Health Emergency & Safety Intervention
        // Note: BPM < 40 or > 140 is a generic placeholder for extreme vitals
        if (bpm > 0 && (bpm < 40 || bpm > 140)) {
            if (now - lastEmergencyTrigger > DEBOUNCE_MS) {
                lastEmergencyTrigger = now
                Log.w("VisionOrchestrator", "Emergency vitals detected: BPM = $bpm")
                val prompt = "[SYSTEM EVENT: URGENT! The driver's heart rate is dangerously abnormal ($bpm BPM). Interrupt immediately: 'Are you feeling okay? I've noticed a severe anomaly in your vitals. Should I route us to the nearest hospital or pull over and call emergency services?']"
                triggerLLM(prompt)
                
                // Fire navigation intent to hospital for safety
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=nearest+hospital"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }

        // Use-Case 5: Celebrating Good Moments (The "Human Touch")
        if (mood.contains("Happy", ignoreCase = true) || mood.contains("Smile", ignoreCase = true)) {
            if (now - lastJoyTrigger > (DEBOUNCE_MS * 5)) { // Less frequent
                lastJoyTrigger = now
                Log.w("VisionOrchestrator", "Driver is happy!")
                val prompt = "[SYSTEM EVENT: The driver is smiling and looks genuinely happy. Casually chime in with something like: 'Looks like you're really enjoying the drive today! Would you like me to save this route or the current song to your favorites?']"
                triggerLLM(prompt)
            }
        }
    }

    // Use-Case 3: Passenger Awareness & Entertainment
    fun onPassengerMoodDetected(passengerMood: String) {
        val now = System.currentTimeMillis()
        if (passengerMood.contains("Sad", ignoreCase = true)) {
            if (now - lastPassengerTrigger > DEBOUNCE_MS) {
                lastPassengerTrigger = now
                Log.w("VisionOrchestrator", "Sad passenger detected in back seat!")
                val prompt = "[SYSTEM EVENT: A passenger in the back seat seems sad or restless. Proactively whisper/ask the driver: 'The passenger in the back seems restless. Should I start an audiobook or play a trivia game to keep them entertained?']"
                triggerLLM(prompt)
            }
        }
    }

    private fun triggerLLM(systemPrompt: String) {
        orchestrator.triggerProactiveEvent(systemPrompt)
    }
}
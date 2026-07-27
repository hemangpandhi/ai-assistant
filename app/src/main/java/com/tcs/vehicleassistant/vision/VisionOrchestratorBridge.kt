package com.tcs.vehicleassistant.vision

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tcs.vehicleassistant.MediaActionBridge
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.core.AgentRuntime
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VisionOrchestratorBridge(private val context: Context, private val orchestrator: AgentOrchestrator) {

    private val mediaBridge = MediaActionBridge(context)

    private var lastGestureTrigger = 0L
    private var lastStressTrigger = 0L
    private var lastPassengerTrigger = 0L
    private var lastEmergencyTrigger = 0L
    private var lastJoyTrigger = 0L
    private var lastDrowsyTrigger = 0L
    private var currentDriverProfile = "Guest"

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
                    AgentRuntime.mainScope.launch {
                        delay(2000)
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

                AgentRuntime.ioScope.launch {
                    VehicleManager.setTemperature(19f)
                    VehicleManager.setAmbientColor(0, 0, 255) // Cool blue
                }

                val prompt = "[SYSTEM EVENT: The driver's biometric stress is High and they look angry. I have automatically lowered the cabin temperature and changed ambient lighting to a calming blue. Proactively say: 'You seem a bit stressed. I've lowered the temperature and set a calming ambient color. Would you like me to play some relaxing jazz?']"
                triggerLLM(prompt)
            }
        }

        // Use-Case 4: Health Emergency & Safety Intervention
        if (bpm > 0 && (bpm < 40 || bpm > 140)) {
            if (now - lastEmergencyTrigger > DEBOUNCE_MS) {
                lastEmergencyTrigger = now
                Log.w("VisionOrchestrator", "Emergency vitals detected: BPM = $bpm")
                val prompt = "[SYSTEM EVENT: URGENT! The driver's heart rate is dangerously abnormal ($bpm BPM). Interrupt immediately: 'Are you feeling okay? I've noticed a severe anomaly in your vitals. Should I route us to the nearest hospital or pull over and call emergency services?']"
                triggerLLM(prompt)

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=nearest+hospital"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }

        // Use-Case 5: Celebrating Good Moments (The "Human Touch")
        if (mood.contains("Happy", ignoreCase = true) || mood.contains("Smile", ignoreCase = true)) {
            if (now - lastJoyTrigger > (DEBOUNCE_MS * 5)) {
                lastJoyTrigger = now
                Log.w("VisionOrchestrator", "Driver is happy!")
                val prompt = "[SYSTEM EVENT: The driver is smiling and looks genuinely happy. Casually chime in with something like: 'Looks like you're really enjoying the drive today! Would you like me to save this route or the current song to your favorites?']"
                triggerLLM(prompt)
            }
        }

        // WOW Feature 1: Vitality Guard (Active Safety Intervention)
        if (mood == "DROWSY WARNING" || mood == "DISTRACTED") {
            if (now - lastDrowsyTrigger > DEBOUNCE_MS) {
                lastDrowsyTrigger = now
                Log.w("VisionOrchestrator", "Vitality Guard triggered! State: $mood")

                AgentRuntime.ioScope.launch {
                    VehicleManager.writeTemperatureToVhalVerified(16f)
                    VehicleManager.writeFanSpeedToVhalVerified(7)
                    VehicleManager.writeWindowPositionToVhalVerified(50)
                }

                val prompt = "[SYSTEM EVENT: URGENT! The driver is falling asleep or highly distracted. I have automatically blasted the AC to max cold and rolled down the window to wake them up. Proactively speak and say EXACTLY this: 'Hey, keep your eyes on the road! I've lowered the temperature to help wake you up. I see a coffee shop 2 miles ahead—shall I route us there?']"
                triggerLLM(prompt)
            }
        }

        // WOW Feature 4: The Seamless Hand-off (Biometric Memory)
        if (gestureFeedback.driverName != currentDriverProfile) {
            val oldDriver = currentDriverProfile
            currentDriverProfile = gestureFeedback.driverName

            if (currentDriverProfile != "Guest") {
                Log.w("VisionOrchestrator", "Driver Hand-off detected: $oldDriver -> $currentDriverProfile")
                val prompt = "[SYSTEM EVENT: A new driver was biometrically detected in the seat! The current driver is now '$currentDriverProfile'. Greet them warmly by name, confirm you are adjusting the cabin temperature to their preference, and reference something from their memory (like asking if they want to find a sushi restaurant nearby).]"
                triggerLLM(prompt)
            }
        }
    }

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

    var isProactiveSpeechEnabled: Boolean = false

    private fun triggerLLM(systemPrompt: String) {
        if (!isProactiveSpeechEnabled) {
            Log.d("VisionOrchestrator", "Proactive speech disabled; ignoring event: $systemPrompt")
            return
        }
        orchestrator.triggerProactiveEvent(systemPrompt)
    }
}

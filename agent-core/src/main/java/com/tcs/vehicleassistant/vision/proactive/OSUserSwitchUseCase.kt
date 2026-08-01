package com.tcs.vehicleassistant.vision.proactive

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.tcs.vehicleassistant.VehicleManager
import java.lang.reflect.Method

class OSUserSwitchUseCase(private val context: Context) : IProactiveUseCase {
    override val name = "Zero-Touch OS Switch"
    override val priority = PriorityLevel.CRITICAL_SAFETY

    private var lastRecognizedUser = ""
    private var guestCounter = 0

    override fun evaluate(cabinContext: CabinContext): TriggerResult? {
        val recognizedUser = cabinContext.recognizedUser

        if (recognizedUser != "Guest" && recognizedUser?.isNotBlank() == true && recognizedUser != lastRecognizedUser) {
            lastRecognizedUser = recognizedUser ?: ""
            guestCounter = 0
            
            // 1. Trigger OS Switch via ActivityManager reflection
            try {
                Log.d("OSUserSwitchUseCase", "Attempting Zero-Touch profile switch to: $recognizedUser")
                // In a real AAOS, we'd find the user ID corresponding to recognizedUser.
                // For demo, we mock switching to user ID 10 (often the first secondary user).
                val activityManagerClass = Class.forName("android.app.ActivityManager")
                val getServiceMethod: Method = activityManagerClass.getMethod("getService")
                val iActivityManager = getServiceMethod.invoke(null)
                val switchUserMethod: Method = iActivityManager.javaClass.getMethod("switchUser", Int::class.javaPrimitiveType)
                switchUserMethod.invoke(iActivityManager, 10)
            } catch (e: Exception) {
                Log.e("OSUserSwitchUseCase", "Failed to switch OS user via reflection. (Expected if not system signed)", e)
            }

            // 2. Trigger Millimeter-Perfect VHAL adjustments
            VehicleManager.applySavedCabinPreferences(recognizedUser ?: "")

            val prompt = "[SYSTEM EVENT: Face ID matched the Android OS Profile for $recognizedUser. The system automatically switched OS users, applied their millimeter-perfect seat and mirror preferences, and unlocked all vehicle features. Proactively welcome them and mention you adjusted the cabin to their exact specifications.]"
            return TriggerResult(prompt, priority)
            
        } else if (recognizedUser == "Guest") {
            guestCounter++
            // If unknown face detected for ~10 frames (~1 second) and someone else was driving
            if (guestCounter == 10 && lastRecognizedUser != "Guest") {
                lastRecognizedUser = "Guest"
                
                // 1. Switch to Guest Profile (User 0 or 11)
                try {
                    val activityManagerClass = Class.forName("android.app.ActivityManager")
                    val getServiceMethod: Method = activityManagerClass.getMethod("getService")
                    val iActivityManager = getServiceMethod.invoke(null)
                    val switchUserMethod: Method = iActivityManager.javaClass.getMethod("switchUser", Int::class.javaPrimitiveType)
                    switchUserMethod.invoke(iActivityManager, 11) // Mock Guest ID
                } catch (e: Exception) {
                    Log.e("OSUserSwitchUseCase", "Failed to switch to Guest OS user.", e)
                }

                // 2. Trigger VHAL Lockdown
                VehicleManager.lockdownValetMode()

                val prompt = "[SYSTEM EVENT: An unrecognized face is in the driver seat. The system automatically switched the OS to Valet/Guest Mode. The glovebox is electronically locked, trunk access is disabled, and top speed is limited. Inform the user they are in Valet Mode.]"
                return TriggerResult(prompt, priority)
            }
        }

        return null
    }
}
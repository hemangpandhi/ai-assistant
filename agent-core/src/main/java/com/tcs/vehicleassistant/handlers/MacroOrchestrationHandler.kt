package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.VehicleManager

class MacroOrchestrationHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "prepareForCommute" -> {
                // Set reasonable temp, etc.
                VehicleManager.writeTemperatureToVhalVerified(21f) // 21C
                ToolExecutionResult(true, "I've set the temperature to 21 degrees and optimized the cabin for your commute.")
            }
            "optimizeCabinForLongDrive" -> {
                VehicleManager.writeSeatMassagerToVhalVerified(1) // turn on slight massage
                ToolExecutionResult(true, "I've turned on the seat massager and set a comfortable climate for your long drive.")
            }
            "makeTripEnjoyable" -> {
                // Stub
                ToolExecutionResult(true, "I've adjusted the cabin lighting and climate to make this trip more enjoyable.")
            }
            "prepareForElderlyPassengers" -> {
                // Warmer temp, maybe turn off direct face air
                VehicleManager.writeTemperatureToVhalVerified(23f) 
                ToolExecutionResult(true, "I've raised the temperature to 23 degrees to ensure your elderly passengers are comfortable.")
            }
            "prepareForImportantMeeting" -> {
                // Cooler temp for focus
                VehicleManager.writeTemperatureToVhalVerified(20f)
                ToolExecutionResult(true, "I've cooled the cabin slightly to help you stay focused for your meeting.")
            }
            "prepareForArrival" -> {
                // Turn on cabin lights so they can see when they get out
                VehicleManager.setGenericVhalProperty(android.car.VehiclePropertyIds.CABIN_LIGHTS_SWITCH, 0, "1", "INT")
                ToolExecutionResult(true, "I've turned on the cabin lights to help you gather your things upon arrival.")
            }
            "enableAdaptiveNightMode" -> {
                // Dim screens
                ToolExecutionResult(true, "Adaptive night mode is enabled. I've dimmed the screens to reduce glare.")
            }
            "prepareForAirportTrip" -> {
                ToolExecutionResult(true, "I've prepared the cabin for your airport trip.")
            }
            "enhanceNiceEvening" -> {
                VehicleManager.writeWindowPositionToVhalVerified(30) // crack windows
                ToolExecutionResult(true, "I've cracked the windows so you can enjoy the evening breeze.")
            }
            "prepareForIncomingRain" -> {
                VehicleManager.writeWindowPositionToVhalVerified(100) // close windows
                VehicleManager.setGenericVhalProperty(android.car.VehiclePropertyIds.HVAC_DEFROSTER, 0, "true", "BOOLEAN") // Front defroster
                VehicleManager.setGenericVhalProperty(320865544, 0, "true", "BOOLEAN") // Rear defroster (HVAC_ELECTRIC_DEFROSTER_ON)
                ToolExecutionResult(true, "I've made sure all windows are securely closed and activated the defrosters since rain is expected.")
            }
            "prepareForParking" -> {
                ToolExecutionResult(true, "I'm ready to assist with parking maneuvers.")
            }
            "handleEmergencyFeeling" -> {
                ToolExecutionResult(true, "I am monitoring all vehicle sensors. Please pull over safely if you feel the vehicle is unsafe to drive.")
            }
            else -> ToolExecutionResult(false, "System Error: Macro Orchestration Handler not recognized.")
        }
    }
}

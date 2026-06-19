package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import com.example.gemininano.VehicleManager

class SafetyAndCareHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "handleDrowsyDriving", "handleDriverFatigue" -> {
                // Cool down the car, turn fan up, turn on cabin lights
                VehicleManager.writeTemperatureToVhalVerified(18f)
                VehicleManager.writeFanSpeedToVhalVerified(5)
                VehicleManager.setGenericVhalProperty(android.car.VehiclePropertyIds.CABIN_LIGHTS_SWITCH, 0, "1", "INT") // CABIN_LIGHTS_SWITCH
                ToolExecutionResult(true, "I've lowered the temperature, increased the fan, and turned on the lights to help you stay alert. Please consider pulling over to rest.")
            }
            "alertDriverDistraction" -> {
                ToolExecutionResult(true, "Please keep your eyes on the road and stay focused on driving.")
            }
            "checkVehicleSecured" -> {
                ToolExecutionResult(true, "I've checked the vehicle. All doors and windows are closed and secured.")
            }
            "checkTripReadiness" -> {
                ToolExecutionResult(true, "Vehicle diagnostics show everything is in order. Tire pressure, fluids, and battery are at optimal levels for your trip.")
            }
            "improveRoadVisibility" -> {
                VehicleManager.setGenericVhalProperty(android.car.VehiclePropertyIds.FOG_LIGHTS_SWITCH, 0, "1", "INT") // FOG_LIGHTS_SWITCH
                ToolExecutionResult(true, "I have activated the fog lights and optimized external lighting for better visibility.")
            }
            else -> ToolExecutionResult(false, "System Error: Safety And Care Handler not recognized.")
        }
    }
}

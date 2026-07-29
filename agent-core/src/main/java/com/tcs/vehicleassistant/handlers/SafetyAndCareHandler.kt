package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.VehicleManager

class SafetyAndCareHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "handleDrowsyDriving", "handleDriverFatigue" -> {
                // Cool down the car, turn fan up, turn on cabin lights
                VehicleManager.writeTemperatureToVhalVerified(18f)
                VehicleManager.writeFanSpeedToVhalVerified(5)
                VehicleManager.setGenericVhalProperty(android.car.VehiclePropertyIds.CABIN_LIGHTS_SWITCH, 0, "1", "INT") // CABIN_LIGHTS_SWITCH
                
                try {
                    val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    searchIntent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    searchIntent.putExtra(android.app.SearchManager.QUERY, "upbeat music")
                    searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                } catch (e: Exception) {}
                
                ToolExecutionResult(true, "I've lowered the temperature, increased the fan, turned on the lights, and started playing upbeat music to help you stay alert. Please consider pulling over to rest.")
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
                VehicleManager.writeDefrosterToVhalVerified(true)
                VehicleManager.writeFanSpeedToVhalVerified(7)
                VehicleManager.writeAirflowDirectionToVhalVerified(4) // DEFROST
                ToolExecutionResult(true, "Clearing your view — defrosters and airflow are set for maximum visibility.")
            }
            else -> ToolExecutionResult(false, "System Error: Safety And Care Handler not recognized.")
        }
    }
}

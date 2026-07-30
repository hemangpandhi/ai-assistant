package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.VehicleManager

class SafetyAndCareHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "handleDrowsyDriving", "handleDriverFatigue" -> {
                val tempOk = VehicleManager.writeTemperatureToVhalVerified(18f)
                val fanOk = VehicleManager.writeFanSpeedToVhalVerified(5)
                val lightsOk = VehicleManager.setGenericVhalProperty(
                    android.car.VehiclePropertyIds.CABIN_LIGHTS_SWITCH, 0, "1", "INT",
                )

                try {
                    val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    searchIntent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    searchIntent.putExtra(android.app.SearchManager.QUERY, "upbeat music")
                    searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                } catch (_: Exception) {
                }

                val actuated = listOfNotNull(
                    "temperature".takeIf { tempOk },
                    "fan".takeIf { fanOk },
                    "cabin lights".takeIf { lightsOk },
                )
                val msg = if (actuated.isNotEmpty()) {
                    "I've adjusted ${actuated.joinToString(" and ")} to help you stay alert. Please consider pulling over to rest."
                } else {
                    "I couldn't confirm climate changes on the vehicle hardware. Please pull over safely if you feel drowsy."
                }
                ToolExecutionResult(actuated.isNotEmpty(), msg)
            }
            "alertDriverDistraction" -> {
                ToolExecutionResult(true, "Please keep your eyes on the road and stay focused on driving.")
            }
            "checkVehicleSecured" -> {
                ToolExecutionResult(true, VehicleManager.getVehicleSecuredStatus())
            }
            "checkTripReadiness" -> {
                val fuelPct = com.tcs.vehicleassistant.core.VehicleUnits.normalizeFuelLevelPct(
                    VehicleManager.getFuelLevel(),
                )
                val windows = VehicleManager.getWindowClosureStatus()
                val gear = VehicleManager.getGearSelection()
                val fuelPart = when {
                    fuelPct < 0 -> "Fuel level isn't available from the vehicle sensors."
                    fuelPct <= 15 -> "Fuel is low at about $fuelPct%."
                    else -> "Fuel is about $fuelPct%."
                }
                val msg = "$fuelPart $windows Current gear: $gear. " +
                    "I don't have tire-pressure or fluid sensors on this build, so I can't certify a full pre-trip inspection."
                ToolExecutionResult(true, msg.trim())
            }
            "improveRoadVisibility" -> {
                val fogOk = VehicleManager.setGenericVhalProperty(
                    android.car.VehiclePropertyIds.FOG_LIGHTS_SWITCH, 0, "1", "INT",
                )
                val defrostOk = VehicleManager.writeDefrosterToVhalVerified(true)
                val fanOk = VehicleManager.writeFanSpeedToVhalVerified(7)
                val airflowOk = VehicleManager.writeAirflowDirectionToVhalVerified(4) // DEFROST
                val ok = fogOk || defrostOk || fanOk || airflowOk
                ToolExecutionResult(
                    ok,
                    if (ok) {
                        "Clearing your view — defrosters and airflow are set for maximum visibility."
                    } else {
                        "I couldn't confirm visibility changes on the vehicle hardware."
                    },
                )
            }
            else -> ToolExecutionResult(false, "System Error: Safety And Care Handler not recognized.")
        }
    }
}

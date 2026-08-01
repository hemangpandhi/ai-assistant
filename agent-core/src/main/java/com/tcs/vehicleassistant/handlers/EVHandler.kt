package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.VehicleManager

class EVHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "suggestOptimizedChargingRate" -> {
                ToolExecutionResult(
                    false,
                    "I don't have battery-management telemetry on this edge build, so I can't recommend a specific charging rate.",
                )
            }
            "optimizeEnergyForRange" -> {
                val tempOk = VehicleManager.writeTemperatureToVhalVerified(24f)
                val fanOk = VehicleManager.writeFanSpeedToVhalVerified(2)
                val ok = tempOk || fanOk
                ToolExecutionResult(
                    ok,
                    if (ok) {
                        "I've reduced climate load to help conserve energy. Regenerative-braking level isn't available on this vehicle property set."
                    } else {
                        "I couldn't confirm eco climate changes on the vehicle hardware."
                    },
                )
            }
            "explainLowRange" -> {
                val speedMph = VehicleManager.getRealSpeed()
                val fuelOrEnergyNote = when {
                    speedMph > 65 -> "Recent higher speeds ($speedMph mph) usually reduce range."
                    else -> "Cabin climate and driving style usually affect remaining range."
                }
                ToolExecutionResult(
                    true,
                    "$fuelOrEnergyNote I don't have battery temperature or pack SoC sensors here, so I can't give a calibrated range diagnosis. Eco climate mode can help if you want me to enable it.",
                )
            }
            else -> ToolExecutionResult(false, "System Error: EV Handler not recognized.")
        }
    }
}

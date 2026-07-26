package com.tcs.vehicleassistant.assistant

import com.test.design.assistant.api.AssistantCabinContext
import com.tcs.vehicleassistant.VehicleManager
import kotlin.math.roundToInt

/**
 * Process-wide cabin snapshot for [VehicleAssistantHost].
 * Updated from vehicle telemetry so the Compose assistant never imports VehicleManager.
 */
object VehicleCabinContextStore {
    @Volatile
    var latest: AssistantCabinContext = AssistantCabinContext()
        private set

    fun publishFromVehicleManager() {
        val speed = VehicleManager.getRealSpeed()
        val gear = VehicleManager.getGearSelection()
        val fuel = VehicleManager.getFuelLevel().coerceIn(0f, 100f)
        val drivingUx = when {
            gear.equals("Park", ignoreCase = true) || speed <= 0 -> "Parked"
            speed > 0 -> "Driving"
            else -> "Parked"
        }
        latest = AssistantCabinContext(
            drivingUx = drivingUx,
            speedMph = speed,
            gear = gear,
            batteryPercent = fuel.roundToInt(),
            rangeMiles = null,
            isCharging = false,
            chargeRateKw = null,
        )
    }
}

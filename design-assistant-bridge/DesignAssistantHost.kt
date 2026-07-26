package com.test.design.presentation.assistant

import android.app.Application
import android.content.Intent
import com.test.design.assistant.api.AssistantCabinContext
import com.test.design.assistant.api.AssistantHost
import com.test.design.core.DrivingUxState
import com.test.design.core.cluster.ClusterUiState
import com.test.design.presentation.ivi.glanceables.DrivingStatusGlanceActivity
import com.test.design.presentation.ivi.vehicle.VehicleUiState

/**
 * Process-wide cabin snapshot for [DesignAssistantHost].
 * Updated from [com.test.design.presentation.DesignAppShell] so the assistant
 * module never imports vehicle ViewModels.
 */
object DesignCabinContextStore {
    @Volatile
    var latest: AssistantCabinContext = AssistantCabinContext()
        private set

    fun publish(drivingUx: DrivingUxState, vehicle: VehicleUiState) {
        val cluster = ClusterUiState.fromDrivingUx(drivingUx)
        latest = AssistantCabinContext(
            drivingUx = drivingUx.name,
            speedMph = cluster.speedMph,
            gear = cluster.gear,
            batteryPercent = vehicle.batteryPercent,
            rangeMiles = vehicle.rangeMiles,
            isCharging = vehicle.isCharging,
            chargeRateKw = vehicle.chargeRateKw,
        )
    }
}

/**
 * IVI host bridge — cluster hand-off + cabin context.
 * A future standalone assistant APK provides its own [AssistantHost].
 */
class DesignAssistantHost(
    private val app: Application,
) : AssistantHost {
    override fun cabinContext(): AssistantCabinContext = DesignCabinContextStore.latest

    override fun openClusterHandOff() {
        runCatching {
            app.startActivity(
                Intent(app, DrivingStatusGlanceActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
            )
        }
    }
}

package com.assistant.ui.assistant.host

import android.app.Application
import android.content.Intent
import com.assistant.ui.assistant.api.AssistantCabinContext
import com.assistant.ui.assistant.api.AssistantHost
import com.assistant.ui.core.DrivingUxState
import com.assistant.ui.core.cluster.ClusterUiState
import com.assistant.ui.presentation.ivi.glanceables.DrivingStatusGlanceActivity
import com.assistant.ui.presentation.ivi.vehicle.VehicleUiState

/**
 * Process-wide cabin snapshot for [DesignAssistantHost].
 * Updated from the host DesignAppShell so the assistant
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

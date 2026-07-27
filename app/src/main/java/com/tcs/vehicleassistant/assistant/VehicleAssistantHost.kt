package com.tcs.vehicleassistant.assistant

import android.app.Application
import android.content.Intent
import android.util.Log
import com.assistant.ui.assistant.api.AssistantCabinContext
import com.assistant.ui.assistant.api.AssistantHost
import com.tcs.vehicleassistant.CockpitAwarenessActivity
import com.assistant.ui.assistant.api.AssistantRuntime

/**
 * Host bridge for the Compose assistant module — cabin context + optional cluster hand-off.
 * View layer must not import this type; it only reaches Compose via [com.assistant.ui.assistant.api.AssistantRuntime].
 */
class VehicleAssistantHost(
    private val app: Application,
) : AssistantHost {
    override fun cabinContext(): AssistantCabinContext {
        VehicleCabinContextStore.publishFromVehicleManager()
        return VehicleCabinContextStore.latest
    }

    override fun openClusterHandOff() {
        runCatching {
            app.startActivity(
                Intent(app, CockpitAwarenessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Log.w(TAG, "Cluster hand-off failed", it)
        }
    }

    companion object {
        private const val TAG = "VehicleAssistantHost"
    }
}

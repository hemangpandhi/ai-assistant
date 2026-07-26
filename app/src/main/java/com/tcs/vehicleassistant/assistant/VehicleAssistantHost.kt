package com.tcs.vehicleassistant.assistant

import android.app.Application
import android.content.Intent
import android.util.Log
import com.test.design.assistant.api.AssistantCabinContext
import com.test.design.assistant.api.AssistantHost
import com.tcs.vehicleassistant.CockpitAwarenessActivity

/**
 * Host bridge for the Compose assistant module — cabin context + optional cluster hand-off.
 * View layer must not import this type; it only reaches Compose via [com.test.design.assistant.api.AssistantRuntime].
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

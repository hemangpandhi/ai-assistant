package com.tcs.vehicleassistant.assistant

import android.app.Application
import com.tcs.vehicleassistant.DemoSettingsPresets
import com.tcs.vehicleassistant.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * UI application entry — keeps refactor [com.tcs.vehicleassistant.VehicleApplication]
 * byte-identical. Point the manifest `android:name` here.
 *
 * Loads master [appModule] only (no TTFR / parallel-agent uiUxModule).
 */
class UiUxVehicleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger()
                androidContext(this@UiUxVehicleApplication)
                modules(appModule)
            }
        }

        DemoSettingsPresets.ensureDefaults(this)
        AssistantRuntimeBootstrap.install(this, useDemoBackend = false)
        LocalLlmPlacementSettingsHook.install(this)
    }
}

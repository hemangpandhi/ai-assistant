package com.tcs.vehicleassistant.assistant

import android.app.Application
import com.tcs.vehicleassistant.DemoSettingsPresets
import com.tcs.vehicleassistant.di.appModule
import com.tcs.vehicleassistant.di.uiUxModule
import com.tcs.vehicleassistant.wakeword.UiUxWakeWordService
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * UI/UX application entry — keeps refactor [com.tcs.vehicleassistant.VehicleApplication]
 * byte-identical. Point the manifest `android:name` here.
 */
class UiUxVehicleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@UiUxVehicleApplication)
            modules(appModule, uiUxModule)
        }

        DemoSettingsPresets.ensureDefaults(this)
        UiUxWakeWordService.bindHoldContext(this)
        AssistantRuntimeBootstrap.install(this, useDemoBackend = false)
        LocalLlmPlacementSettingsHook.install(this)
    }
}

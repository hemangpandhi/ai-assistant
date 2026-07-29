package com.tcs.vehicleassistant

import android.app.Application
import com.tcs.vehicleassistant.assistant.AssistantRuntimeBootstrap
import com.tcs.vehicleassistant.di.appModule
import com.tcs.vehicleassistant.di.uiUxModule
import com.tcs.vehicleassistant.wakeword.UiUxWakeWordService
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class VehicleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@VehicleApplication)
            // ui_ux extension seam
            modules(appModule, uiUxModule)
        }

        DemoSettingsPresets.ensureDefaults(this)
        // ui_ux extension seam
        UiUxWakeWordService.bindHoldContext(this)
        AssistantRuntimeBootstrap.install(this, useDemoBackend = false)
    }
}

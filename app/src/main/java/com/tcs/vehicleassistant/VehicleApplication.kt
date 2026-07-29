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
            // ui_ux extension seam — keep appModule close to refactor; additive bindings here
            modules(appModule, uiUxModule)
        }

        DemoSettingsPresets.ensureDefaults(this)
        // So main-process STT can observe the `:wakeword` mic-hold marker.
        UiUxWakeWordService.bindHoldContext(this)
        // Production agent backend (mic / hotword / LLM / TTS).
        AssistantRuntimeBootstrap.install(this, useDemoBackend = false)
    }
}

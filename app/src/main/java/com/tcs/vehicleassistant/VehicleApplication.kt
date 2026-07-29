package com.tcs.vehicleassistant

import android.app.Application
import com.tcs.vehicleassistant.assistant.AssistantRuntimeBootstrap
import com.tcs.vehicleassistant.di.appModule
import com.tcs.vehicleassistant.di.uiUxModule
import com.tcs.vehicleassistant.wakeword.UiUxWakeWordService
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        
        // Silently pre-compile the AI models in the background when the main app process boots.
        // This eliminates the 20-60 second initialization hang when the user opens the popup.
        if (android.app.Application.getProcessName() == packageName) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                com.tcs.vehicleassistant.LLMManager.autoInitialize(this@VehicleApplication)
            }
        }
    }
}

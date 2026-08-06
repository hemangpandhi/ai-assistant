package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.llm.LLMManager

import android.app.Application
import com.tcs.vehicleassistant.di.appModule
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
            modules(appModule)
        }

        DemoSettingsPresets.ensureDefaults(this)
        com.tcs.vehicleassistant.core.AssistantConfig.migrateMicThrashPrefs(this)

        // Silently pre-compile the AI models in the background when the main app process boots.
        // This eliminates the 20-60 second initialization hang when the user opens the popup.
        if (android.app.Application.getProcessName() == packageName) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                com.tcs.vehicleassistant.llm.LLMManager.autoInitialize(this@VehicleApplication)
            }
        }
    }
}

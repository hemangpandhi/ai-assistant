package com.tcs.vehicleassistant

import android.app.Application
import com.tcs.vehicleassistant.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class VehicleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger()
            androidContext(this@VehicleApplication)
            modules(appModule)
        }

        DemoSettingsPresets.ensureDefaults(this)
    }
}

package com.tcs.vehicleassistant

import android.content.Context
import java.util.Locale

data class DemoPreset(
    val id: String,
    val displayName: String,
    val longitude: Double,
    val latitude: Double,
    val cityName: String,
    val homeAddress: String = "Home",
    val workAddress: String = "Work",
    val mechanicName: String = "Mechanic",
    val mechanicNumber: String = "1-800-555-0199",
    val diningPref: String = "Pure Vegetarian",
    val companionMode: Boolean = true,
    val agenticLoop: Boolean = true
) {
    val coordinates: Pair<Double, Double> = Pair(longitude, latitude)

    fun coordinatesString(): String =
        String.format(Locale.US, "%.4f, %.4f", longitude, latitude)
}

object DemoSettingsPresets {
    const val PREF_INITIALIZED = "demo_settings_initialized"

    val TOKYO_OEM = DemoPreset(
        id = "tokyo_oem",
        displayName = "OEM Demo — Tokyo",
        longitude = 139.6917,
        latitude = 35.6895,
        cityName = "Tokyo",
        homeAddress = "Shibuya, Tokyo",
        workAddress = "Marunouchi, Tokyo"
    )

    val SAGAMIHARA = DemoPreset(
        id = "sagamihara",
        displayName = "Dev — Sagamihara",
        longitude = 139.37,
        latitude = 35.57,
        cityName = "Sagamihara"
    )

    val ALL = listOf(TOKYO_OEM, SAGAMIHARA)

    fun findById(id: String?): DemoPreset? = ALL.find { it.id == id }

    fun getSelected(context: Context): DemoPreset {
        val id = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString(LocationManager.PREF_DEMO_PRESET, TOKYO_OEM.id) ?: TOKYO_OEM.id
        return findById(id) ?: TOKYO_OEM
    }

    fun apply(context: Context, preset: DemoPreset) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().apply {
            putString(LocationManager.PREF_DEMO_PRESET, preset.id)
            putString(LocationManager.PREF_LOCATION_OVERRIDE, preset.coordinatesString())
            putString(LocationManager.PREF_DEMO_CITY, preset.cityName)
            putString("home_address", preset.homeAddress)
            putString("work_address", preset.workAddress)
            putString("mechanic_name", preset.mechanicName)
            putString("mechanic_number", preset.mechanicNumber)
            putString("dining_pref", preset.diningPref)
            putBoolean("companion_mode_enabled", preset.companionMode)
            putBoolean("agentic_loop_enabled", preset.agenticLoop)
            apply()
        }
    }

    fun ensureDefaults(context: Context) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(LocationManager.PREF_LOCATION_SOURCE, LocationManager.Source.DEVICE.prefValue)
            .putBoolean("cloud_model_active", false)
            .putBoolean("cloud_fallback_enabled", false)
            .putString("selected_model", "/data/local/tmp/llm/model.litertlm")
            .putBoolean(PREF_INITIALIZED, true)
            .apply()
        apply(context, TOKYO_OEM)
    }
}

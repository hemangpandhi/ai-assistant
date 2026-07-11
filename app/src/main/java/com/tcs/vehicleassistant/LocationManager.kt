package com.tcs.vehicleassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

object LocationManager {
    private const val TAG = "VehicleLocationManager"

    const val PREF_LOCATION_SOURCE = "location_source"
    const val PREF_DEMO_PRESET = "demo_preset"
    const val PREF_LOCATION_OVERRIDE = "location_override"
    const val PREF_DEMO_CITY = "demo_city_name"
    const val PREF_LAST_DEVICE_LOCATION = "last_device_location"

    enum class Source(val prefValue: String, val label: String) {
        DEVICE("device", "Device GPS (fallback to preset)"),
        MANUAL("manual", "Manual coordinates"),
        PRESET("preset", "Demo preset only");

        companion object {
            fun fromPref(value: String?): Source =
                entries.find { it.prefValue == value } ?: DEVICE
        }
    }

    fun getCoordinates(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return when (Source.fromPref(prefs.getString(PREF_LOCATION_SOURCE, Source.DEVICE.prefValue))) {
            Source.MANUAL -> parseCoordinates(prefs.getString(PREF_LOCATION_OVERRIDE, null))
                ?: DemoSettingsPresets.getSelected(context).coordinates
            Source.PRESET -> DemoSettingsPresets.getSelected(context).coordinates
            Source.DEVICE -> getDeviceCoordinates(context)
                ?: parseCoordinates(prefs.getString(PREF_LOCATION_OVERRIDE, null))
                ?: DemoSettingsPresets.getSelected(context).coordinates
        }
    }

    fun getBbox(context: Context): String {
        val (lon, lat) = getCoordinates(context)
        return "${lat - 0.1},${lon - 0.1},${lat + 0.1},${lon + 0.1}"
    }

    fun getCoordinatesString(context: Context): String {
        val (lon, lat) = getCoordinates(context)
        return String.format(Locale.US, "%.4f, %.4f", lon, lat)
    }

    fun getCurrentCity(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val source = Source.fromPref(prefs.getString(PREF_LOCATION_SOURCE, null))

        if (source == Source.DEVICE) {
            reverseGeocodeCity(context)?.let { return it }
        }

        prefs.getString(PREF_DEMO_CITY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return DemoSettingsPresets.getSelected(context).cityName
    }

    fun getLocationStatus(context: Context): String {
        val source = Source.fromPref(
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString(PREF_LOCATION_SOURCE, Source.DEVICE.prefValue)
        )
        val city = getCurrentCity(context)
        val coords = getCoordinatesString(context)
        return "$city ($coords) via ${source.label}"
    }

    private fun getDeviceCoordinates(context: Context): Pair<Double, Double>? {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return readCachedDeviceCoordinates(context)

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (provider in providers) {
                if (!lm.isProviderEnabled(provider)) continue
                val loc = try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
                if (loc != null && (best == null || loc.time > best.time)) {
                    best = loc
                }
            }
            if (best != null) {
                val coords = Pair(best.longitude, best.latitude)
                cacheDeviceCoordinates(context, coords)
                coords
            } else {
                readCachedDeviceCoordinates(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device location unavailable", e)
            readCachedDeviceCoordinates(context)
        }
    }

    private fun cacheDeviceCoordinates(context: Context, coords: Pair<Double, Double>) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString(PREF_LAST_DEVICE_LOCATION, "${coords.first}, ${coords.second}")
            .apply()
    }

    private fun readCachedDeviceCoordinates(context: Context): Pair<Double, Double>? =
        parseCoordinates(
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString(PREF_LAST_DEVICE_LOCATION, null)
        )

    private fun reverseGeocodeCity(context: Context): String? {
        val coords = getDeviceCoordinates(context) ?: return null
        return try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(coords.second, coords.first, 1)
            results?.firstOrNull()?.locality
                ?: results?.firstOrNull()?.subAdminArea
                ?: results?.firstOrNull()?.adminArea
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed", e)
            null
        }
    }

    private fun parseCoordinates(raw: String?): Pair<Double, Double>? {
        if (raw.isNullOrBlank() || !raw.contains(",")) return null
        val parts = raw.split(",")
        if (parts.size < 2) return null
        val lon = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        return Pair(lon, lat)
    }
}

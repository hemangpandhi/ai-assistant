package com.tcs.vehicleassistant.core

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager

/**
 * Fact-only cabin/media/geo snapshot used by [ContextGuard]. Values come from VHAL /
 * AudioManager / MediaSession / LocationManager / [NavSessionState] — never from the LLM.
 */
data class CabinSnapshot(
    val mediaVolumePct: Int,
    val mediaPlaying: Boolean,
    val fanLevel: Int,
    val fanMax: Int = DEFAULT_FAN_MAX,
    val cabinTempF: Int,
    val seatHeaterLevel: Int,
    val seatHeaterMax: Int = DEFAULT_SEAT_HEATER_MAX,
    val acOn: Boolean,
    val hvacPowerOn: Boolean,
    val hvacAutoOn: Boolean = false,
    val defrostOn: Boolean,
    val speedMph: Int,
    val gear: String,
    /** True when gear reports Park (or P). */
    val isParked: Boolean = gearLooksParked(gear),
    /** Fuel level 0–100; -1 if unknown. */
    val fuelLevelPct: Int = -1,
    /** Best-effort window openness 0–100; -1 if unknown. */
    val windowOpenPct: Int = -1,
    val navActiveDest: String? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val navActive: Boolean get() = !navActiveDest.isNullOrBlank()

    fun sensor(name: String): Double? = when (name.lowercase()) {
        "media_volume_pct", "volume_pct", "volume" -> mediaVolumePct.toDouble()
        "media_playing", "playing" -> if (mediaPlaying) 1.0 else 0.0
        "fan_level", "fan" -> fanLevel.toDouble()
        "fan_max" -> fanMax.toDouble()
        "cabin_temp_f", "cabin_temp", "temp_f" -> cabinTempF.toDouble()
        "seat_heater_level", "seat_heater" -> seatHeaterLevel.toDouble()
        "seat_heater_max" -> seatHeaterMax.toDouble()
        "ac_on" -> if (acOn) 1.0 else 0.0
        "hvac_power_on" -> if (hvacPowerOn) 1.0 else 0.0
        "hvac_auto_on" -> if (hvacAutoOn) 1.0 else 0.0
        "defrost_on" -> if (defrostOn) 1.0 else 0.0
        "speed_mph", "speed" -> speedMph.toDouble()
        "is_parked", "parked" -> if (isParked) 1.0 else 0.0
        "fuel_level_pct", "fuel_pct", "fuel" -> if (fuelLevelPct >= 0) fuelLevelPct.toDouble() else null
        "window_open_pct" -> if (windowOpenPct >= 0) windowOpenPct.toDouble() else null
        "nav_active" -> if (navActive) 1.0 else 0.0
        "latitude", "lat" -> latitude
        "longitude", "lon", "lng" -> longitude
        else -> null
    }

    fun interpolate(template: String): String {
        var out = template
        val values = mapOf(
            "media_volume_pct" to mediaVolumePct.toString(),
            "volume_pct" to mediaVolumePct.toString(),
            "fan_level" to fanLevel.toString(),
            "fan_max" to fanMax.toString(),
            "cabin_temp_f" to cabinTempF.toString(),
            "seat_heater_level" to seatHeaterLevel.toString(),
            "speed_mph" to speedMph.toString(),
            "gear" to gear,
            "is_parked" to if (isParked) "yes" else "no",
            "fuel_level_pct" to if (fuelLevelPct >= 0) fuelLevelPct.toString() else "unknown",
            "window_open_pct" to if (windowOpenPct >= 0) windowOpenPct.toString() else "unknown",
            "media_playing" to if (mediaPlaying) "yes" else "no",
            "hvac_auto_on" to if (hvacAutoOn) "on" else "off",
            "nav_active_dest" to (navActiveDest ?: "none"),
            "city" to (city ?: "unknown"),
            "latitude" to (latitude?.toString() ?: "unknown"),
            "longitude" to (longitude?.toString() ?: "unknown"),
        )
        for ((k, v) in values) {
            out = out.replace("{$k}", v, ignoreCase = true)
        }
        return out
    }

    companion object {
        const val DEFAULT_FAN_MAX = 7
        const val DEFAULT_SEAT_HEATER_MAX = 3

        fun gearLooksParked(gear: String): Boolean {
            val g = gear.trim().lowercase()
            return g == "park" || g == "p" || g.startsWith("park")
        }

        /** @see VehicleUnits.normalizeFuelLevelPct */
        fun normalizeFuelLevelPct(fuelRaw: Float): Int = VehicleUnits.normalizeFuelLevelPct(fuelRaw)

        /** @see VehicleUnits.mpsToMph */
        fun speedMpsToMph(mps: Float): Int = VehicleUnits.mpsToMph(mps)
    }
}


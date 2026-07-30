package com.tcs.vehicleassistant.core

/**
 * Unit conversions for VHAL sensor values used by cabin snapshots and ContextGuard.
 *
 * AOSP [android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED] is meters/second.
 * ContextGuard policies and spoken copy use mph ([CabinSnapshot.speedMph]).
 */
object VehicleUnits {
    const val MPS_TO_MPH = 2.2369363f

    /** Convert AOSP PERF_VEHICLE_SPEED (m/s) to whole mph for policy/UI. */
    fun mpsToMph(mps: Float): Int {
        if (mps.isNaN() || mps < 0f) return 0
        return Math.round(mps * MPS_TO_MPH).coerceAtLeast(0)
    }

    /** Convert mph (telemetry mock / UI) back to m/s for storage alongside live VHAL. */
    fun mphToMps(mph: Float): Float {
        if (mph.isNaN() || mph < 0f) return 0f
        return mph / MPS_TO_MPH
    }

    /**
     * Normalize VHAL fuel to 0–100 percent.
     * - NaN / negative → unknown (-1)
     * - 0–1 inclusive → fraction of tank
     * - (1, 100] → already percent (common emulator quirk)
     * - >100 → absolute volume without capacity → unknown (-1), never invent 100%
     */
    fun normalizeFuelLevelPct(fuelRaw: Float): Int = when {
        fuelRaw.isNaN() -> -1
        fuelRaw < 0f -> -1
        fuelRaw <= 1f -> Math.round(fuelRaw * 100f).coerceIn(0, 100)
        fuelRaw <= 100f -> Math.round(fuelRaw).coerceIn(0, 100)
        else -> -1
    }
}

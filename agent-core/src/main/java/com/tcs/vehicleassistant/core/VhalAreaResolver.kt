package com.tcs.vehicleassistant.core

/**
 * Resolves which VHAL area IDs to actuate for a zone request.
 * Never invents OEM-specific area constants (49/68/117/…); callers must supply
 * areas from [android.car.hardware.CarPropertyConfig.getAreaIds].
 */
object VhalAreaResolver {

    /**
     * @param areaIds live areas from the property config (may be empty)
     * @param zone `all` / blank → every area; `driver` / `passenger` prefer seat bitmasks when present
     * @return empty when [areaIds] is empty — do not fall back to hardcoded AOSP demo IDs
     */
    fun filterByZone(areaIds: IntArray, zone: String): List<Int> {
        if (areaIds.isEmpty()) return emptyList()
        val z = zone.trim().lowercase()
        if (z.isEmpty() || z == "all") return areaIds.toList()

        val filtered = when (z) {
            "driver" -> areaIds.filter { (it and 4) == 4 }
            "passenger" -> areaIds.filter { (it and 1) == 1 }
            else -> areaIds.toList()
        }
        // OEM area schemes often omit ROW_1 bitmasks — use the config areas rather than inventing IDs.
        return filtered.ifEmpty { areaIds.toList() }
    }

    fun firstOrNull(areaIds: IntArray): Int? = areaIds.firstOrNull()
}

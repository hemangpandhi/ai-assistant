package com.tcs.vehicleassistant.core

/**
 * Last successfully launched navigation destination (facts only).
 * Updated by [com.tcs.vehicleassistant.handlers.NavigationToolHandler]; read by [CabinSnapshotReader].
 */
object NavSessionState {
    @Volatile
    var activeDest: String? = null
        private set

    fun setActive(dest: String?) {
        val cleaned = dest?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
        if (cleaned.equals("none", ignoreCase = true) || cleaned.equals("null", ignoreCase = true)) {
            activeDest = null
        } else {
            activeDest = cleaned
        }
    }

    fun clear() {
        activeDest = null
    }
}

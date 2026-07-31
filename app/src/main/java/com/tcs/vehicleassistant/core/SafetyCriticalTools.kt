package com.tcs.vehicleassistant.core

/**
 * Handlers that must not fail-open when cabin state cannot be verified.
 * Unlock / trunk / windows while moving are the primary driving-safety surfaces.
 */
object SafetyCriticalTools {

    private val HANDLERS = setOf(
        "unlockdoors",
        "opentrunk",
        "setallwindowsposition",
        "setwindowposition",
        "openwindowsvent",
        "closewindows",
    )

    fun isSafetyCritical(toolCall: String): Boolean {
        val handler = toolCall.substringBefore("(").trim().lowercase()
        return handler in HANDLERS
    }

    const val SNAPSHOT_UNAVAILABLE_POLICY_ID = "snapshot_unavailable_fail_closed"

    const val SNAPSHOT_UNAVAILABLE_MESSAGE =
        "I couldn't verify the vehicle state, so I won't run that for safety. " +
            "Please try again when you're parked."

    const val GEAR_UNKNOWN_POLICY_ID = "gear_unknown_fail_closed"

    fun gearUnknownConfirmMessage(toolCall: String): String {
        val handler = toolCall.substringBefore("(").trim()
        return "I couldn't confirm whether the car is parked. Should I still run $handler?"
    }
}

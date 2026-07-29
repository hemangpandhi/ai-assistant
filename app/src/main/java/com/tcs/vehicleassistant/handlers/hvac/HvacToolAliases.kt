package com.tcs.vehicleassistant.handlers.hvac

/**
 * HVAC handler-key aliases spanning registry + remote (`dev/refactor`) naming.
 *
 * Canonicalize before dispatch so [com.tcs.vehicleassistant.handlers.HVACToolHandler]
 * and the registry stay closer to upstream without duplicated `when` arms.
 */
object HvacToolAliases {

    /** Alias → canonical handler key used by HVACToolHandler. */
    val aliases: Map<String, String> = mapOf(
        "turnOnAc" to "turnOnAC",
        "turnOffAc" to "turnOffAC",
        "turnOnAutoHvac" to "turnOnAutoClimate",
        "turnOffAutoHvac" to "turnOffAutoClimate",
        "turnOnHvac" to "turnOnHvacPower",
        "turnOffHvac" to "turnOffHvacPower",
        "turnOnAirRecirculation" to "turnOnRecirculation",
        "turnOffAirRecirculation" to "turnOffRecirculation",
    )

    fun canonicalize(handlerKey: String): String = aliases[handlerKey] ?: handlerKey

    /** All known HVAC keys including aliases (for registry membership / SmartContext). */
    fun expand(coreKeys: Set<String>): Set<String> = coreKeys + aliases.keys + aliases.values
}

package com.tcs.vehicleassistant.handlers

import com.tcs.vehicleassistant.ToolManager

object ToolHandlerRegistry {
    val hvacHandlers = setOf("setAirflowDirection", "increaseTemperature", "decreaseTemperature", "setTemperature", "setDriverTemperature", "setPassengerTemperature", "increasePassengerTemperature", "decreasePassengerTemperature", "increaseDriverTemperature", "decreaseDriverTemperature", "increaseFanSpeed", "decreaseFanSpeed", "setFanSpeed", "setSeatHeater", "setSeatMassager", "turnOnDefroster", "turnOffDefroster", "turnOnRearDefroster", "turnOffRearDefroster", "turnOnAC", "turnOffAC", "turnOnAutoClimate", "turnOffAutoClimate", "turnOnHvacPower", "turnOffHvacPower", "handleFeelingCold", "enableFreshAirIntake", "protectFromPollutedAir", "defogWindshield", "movePassengerSeatForward", "turnOnRecirculation", "turnOffRecirculation")
    val mediaHandlers = setOf("playMusic", "pauseMusic", "stopMusic", "nextTrack", "prevTrack", "adjustBgmForSituation", "setVolumeLevel")
    val navHandlers = setOf("startNavigationTo", "searchNearby", "search", "suggestNearbyPlaces", "provideLaneLevelGuidance", "suggestAlternateRoute")
    val commHandlers = setOf("call", "bookRestaurant", "queryMemory", "callContact", "sendText")
    val systemHandlers = setOf("remember", "getWeather", "openApp", "sendUpcomingEventReminder", "explainChildSeatInstallation", "suggestUmbrellaIfRainy", "getNewsHighlights", "checkVehicleState", "answerVehicleIdentity", "openTrunk", "setEnergeticCabinLighting", "turnOffCabinLight", "turnOnCabinLight", "unlockDoors", "analyzeCabinState")
    val windowHandlers = setOf("setAllWindowsPosition", "openWindowsSlightly", "closeAllWindows", "setWindowPosition", "checkAllWindowsClosed")
    val macroHandlers = setOf("prepareForCommute", "optimizeCabinForLongDrive", "makeTripEnjoyable", "prepareForElderlyPassengers", "prepareForImportantMeeting", "prepareForArrival", "enableAdaptiveNightMode", "prepareForAirportTrip", "enhanceNiceEvening", "prepareForIncomingRain", "prepareForParking", "handleEmergencyFeeling")
    val safetyHandlers = setOf("handleDrowsyDriving", "handleDriverFatigue", "alertDriverDistraction", "checkVehicleSecured", "checkTripReadiness", "improveRoadVisibility")
    val evHandlers = setOf("suggestOptimizedChargingRate", "optimizeEnergyForRange", "explainLowRange")

    fun getHandler(handlerKey: String, toolDefinition: ToolManager.ToolDefinition): ToolHandler? {

        return when {
            hvacHandlers.contains(handlerKey) -> HVACToolHandler(handlerKey, toolDefinition)
            mediaHandlers.contains(handlerKey) -> MediaToolHandler(handlerKey)
            navHandlers.contains(handlerKey) -> NavigationToolHandler(handlerKey)
            commHandlers.contains(handlerKey) -> CommunicationToolHandler(handlerKey)
            systemHandlers.contains(handlerKey) -> SystemToolHandler(handlerKey, toolDefinition)
            windowHandlers.contains(handlerKey) -> WindowToolHandler(handlerKey, toolDefinition)
            macroHandlers.contains(handlerKey) -> MacroOrchestrationHandler(handlerKey)
            safetyHandlers.contains(handlerKey) -> SafetyAndCareHandler(handlerKey)
            evHandlers.contains(handlerKey) -> EVHandler(handlerKey)
            else -> null
        }
    }

    /** Every CUSTOM_KOTLIN handler_key the registry may declare. */
    fun allRegisteredKeys(): Set<String> =
        hvacHandlers + mediaHandlers + navHandlers + commHandlers +
            systemHandlers + windowHandlers + macroHandlers + safetyHandlers + evHandlers

    /**
     * Returns CUSTOM_KOTLIN handler keys from [tools] that have no registered implementation.
     * Call after loading the registry so a missing `when` branch fails at init, not on first use.
     */
    fun missingHandlers(tools: Map<String, ToolManager.ToolDefinition>): List<String> =
        tools.values
            .filter { it.handlerType == "CUSTOM_KOTLIN" }
            .mapNotNull { it.handlerKey }
            .filter { it !in allRegisteredKeys() }
            .distinct()
            .sorted()
}

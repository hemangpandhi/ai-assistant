package com.tcs.vehicleassistant.handlers

import com.tcs.vehicleassistant.domain.tools.ToolDefinition

interface IToolHandlerRegistry {
    fun getHandler(handlerKey: String, toolDefinition: ToolDefinition): ToolHandler?
    fun allRegisteredKeys(): Set<String>
    fun missingHandlers(tools: Map<String, ToolDefinition>): List<String>
}

class DefaultToolHandlerRegistry : IToolHandlerRegistry {
    private val hvacHandlers = setOf("setAirflowDirection", "increaseTemperature", "decreaseTemperature", "setTemperature", "setDriverTemperature", "setPassengerTemperature", "increasePassengerTemperature", "decreasePassengerTemperature", "increaseDriverTemperature", "decreaseDriverTemperature", "increaseFanSpeed", "decreaseFanSpeed", "setFanSpeed", "setSeatHeater", "setSeatMassager", "turnOnDefroster", "turnOffDefroster", "turnOnRearDefroster", "turnOffRearDefroster", "turnOnAC", "turnOffAC", "turnOnAutoClimate", "turnOffAutoClimate", "turnOnHvacPower", "turnOffHvacPower", "handleFeelingCold", "enableFreshAirIntake", "protectFromPollutedAir", "defogWindshield", "movePassengerSeatForward", "turnOnRecirculation", "turnOffRecirculation")
    private val mediaHandlers = setOf("playMusic", "pauseMusic", "stopMusic", "nextTrack", "prevTrack", "adjustBgmForSituation", "setVolumeLevel")
    private val navHandlers = setOf("startNavigationTo", "searchNearby", "search", "suggestNearbyPlaces", "provideLaneLevelGuidance", "suggestAlternateRoute")
    private val commHandlers = setOf("call", "bookRestaurant", "queryMemory", "callContact", "sendText")
    private val systemHandlers = setOf("remember", "getWeather", "openApp", "openClimateScreen", "openVehicleScreen", "sendUpcomingEventReminder", "explainChildSeatInstallation", "suggestUmbrellaIfRainy", "getNewsHighlights", "checkVehicleState", "answerVehicleIdentity", "openTrunk", "setEnergeticCabinLighting", "turnOffCabinLight", "turnOnCabinLight", "unlockDoors", "analyzeCabinState")
    private val windowHandlers = setOf("setAllWindowsPosition", "openWindowsSlightly", "closeAllWindows", "setWindowPosition", "checkAllWindowsClosed")
    private val macroHandlers = setOf("prepareForCommute", "optimizeCabinForLongDrive", "makeTripEnjoyable", "prepareForElderlyPassengers", "prepareForImportantMeeting", "prepareForArrival", "enableAdaptiveNightMode", "prepareForAirportTrip", "enhanceNiceEvening", "prepareForIncomingRain", "prepareForParking", "handleEmergencyFeeling")
    private val safetyHandlers = setOf("handleDrowsyDriving", "handleDriverFatigue", "alertDriverDistraction", "checkVehicleSecured", "checkTripReadiness", "improveRoadVisibility")
    private val evHandlers = setOf("suggestOptimizedChargingRate", "optimizeEnergyForRange", "explainLowRange")
    private val shoppingHandlers = setOf("searchAmazon", "purchaseAmazonItem")

    override fun getHandler(handlerKey: String, toolDefinition: ToolDefinition): ToolHandler? {
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
            shoppingHandlers.contains(handlerKey) -> ShoppingToolHandler(handlerKey)
            else -> null
        }
    }

    override fun allRegisteredKeys(): Set<String> =
        hvacHandlers + mediaHandlers + navHandlers + commHandlers +
            systemHandlers + windowHandlers + macroHandlers + safetyHandlers + evHandlers + shoppingHandlers

    override fun missingHandlers(tools: Map<String, ToolDefinition>): List<String> =
        tools.values
            .filter { it.handlerType == "CUSTOM_KOTLIN" }
            .mapNotNull { it.handlerKey }
            .filter { it !in allRegisteredKeys() }
            .distinct()
            .sorted()
}

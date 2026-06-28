package com.tcs.vehicleassistant.handlers

import com.tcs.vehicleassistant.ToolManager

object ToolHandlerRegistry {
    val hvacHandlers = setOf("setAirflowDirection", "increaseTemperature", "decreaseTemperature", "setTemperature", "setDriverTemperature", "setPassengerTemperature", "increasePassengerTemperature", "decreasePassengerTemperature", "increaseDriverTemperature", "decreaseDriverTemperature", "increaseFanSpeed", "decreaseFanSpeed", "setFanSpeed", "setSeatHeater", "setSeatMassager", "turnOnDefroster", "turnOffDefroster", "turnOnRearDefroster", "turnOffRearDefroster", "turnOnAC", "turnOffAC", "turnOnAutoClimate", "turnOffAutoClimate", "turnOnHvacPower", "turnOffHvacPower", "handleFeelingCold", "enableFreshAirIntake", "protectFromPollutedAir", "defogWindshield", "movePassengerSeatForward")
    val mediaHandlers = setOf("playMusic", "pauseMusic", "stopMusic", "nextTrack", "prevTrack", "adjustBgmForSituation", "setVolumeLevel")
    val navHandlers = setOf("startNavigationTo", "searchNearby", "search", "suggestNearbyPlaces", "provideLaneLevelGuidance", "suggestAlternateRoute")
    val commHandlers = setOf("call", "bookRestaurant", "queryMemory", "callContact", "sendText")
    val systemHandlers = setOf("remember", "getWeather", "openApp", "sendUpcomingEventReminder", "explainChildSeatInstallation", "suggestUmbrellaIfRainy", "getNewsHighlights", "checkVehicleState")
    val windowHandlers = setOf("setAllWindowsPosition", "openWindowsSlightly", "closeAllWindows", "setWindowPosition")
    val macroHandlers = setOf("prepareForCommute", "optimizeCabinForLongDrive", "makeTripEnjoyable", "prepareForElderlyPassengers", "prepareForImportantMeeting", "prepareForArrival", "enableAdaptiveNightMode", "prepareForAirportTrip", "enhanceNiceEvening", "prepareForIncomingRain", "prepareForParking", "handleEmergencyFeeling")
    val safetyHandlers = setOf("handleDrowsyDriving", "handleDriverFatigue", "alertDriverDistraction", "checkVehicleSecured", "checkTripReadiness", "improveRoadVisibility")
    val evHandlers = setOf("suggestOptimizedChargingRate", "optimizeEnergyForRange", "explainLowRange")

    fun getHandler(handlerKey: String, toolDefinition: ToolManager.ToolDefinition): ToolHandler? {

        return when {
            hvacHandlers.contains(handlerKey) -> HVACToolHandler(handlerKey, toolDefinition)
            mediaHandlers.contains(handlerKey) -> MediaToolHandler(handlerKey)
            navHandlers.contains(handlerKey) -> NavigationToolHandler(handlerKey)
            commHandlers.contains(handlerKey) -> CommunicationToolHandler(handlerKey)
            systemHandlers.contains(handlerKey) -> SystemToolHandler(handlerKey)
            windowHandlers.contains(handlerKey) -> WindowToolHandler(handlerKey)
            macroHandlers.contains(handlerKey) -> MacroOrchestrationHandler(handlerKey)
            safetyHandlers.contains(handlerKey) -> SafetyAndCareHandler(handlerKey)
            evHandlers.contains(handlerKey) -> EVHandler(handlerKey)
            else -> null
        }
    }
}

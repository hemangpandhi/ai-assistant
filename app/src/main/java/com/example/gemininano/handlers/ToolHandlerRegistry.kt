package com.example.gemininano.handlers

import com.example.gemininano.ToolManager

object ToolHandlerRegistry {
    fun getHandler(handlerKey: String, toolDefinition: ToolManager.ToolDefinition): ToolHandler? {
        val hvacHandlers = setOf("setAirflowDirection", "increaseTemperature", "decreaseTemperature", "setTemperature", "increaseFanSpeed", "decreaseFanSpeed", "setFanSpeed", "setSeatHeater", "setSeatMassager", "turnOnDefroster", "turnOffDefroster", "turnOnRearDefroster", "turnOffRearDefroster", "handleFeelingCold", "enableFreshAirIntake", "protectFromPollutedAir", "defogWindshield", "movePassengerSeatForward")
        val mediaHandlers = setOf("playMusic", "pauseMusic", "nextTrack", "prevTrack", "adjustBgmForSituation")
        val navHandlers = setOf("navigate", "searchNearby", "search", "suggestNearbyPlaces", "provideLaneLevelGuidance", "suggestAlternateRoute")
        val commHandlers = setOf("call", "bookRestaurant", "queryMemory", "callContact", "sendText")
        val systemHandlers = setOf("remember", "getWeather", "openApp", "sendUpcomingEventReminder", "explainChildSeatInstallation", "suggestUmbrellaIfRainy", "getNewsHighlights")
        val windowHandlers = setOf("setAllWindowsPosition", "openWindowsSlightly", "closeAllWindows")
        val macroHandlers = setOf("prepareForCommute", "optimizeCabinForLongDrive", "makeTripEnjoyable", "prepareForElderlyPassengers", "prepareForImportantMeeting", "prepareForArrival", "enableAdaptiveNightMode", "prepareForAirportTrip", "enhanceNiceEvening", "prepareForIncomingRain", "prepareForParking", "handleEmergencyFeeling")
        val safetyHandlers = setOf("handleDrowsyDriving", "handleDriverFatigue", "alertDriverDistraction", "checkVehicleSecured", "checkTripReadiness", "improveRoadVisibility")
        val evHandlers = setOf("suggestOptimizedChargingRate", "optimizeEnergyForRange", "explainLowRange")

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

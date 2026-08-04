package com.tcs.vehicleassistant

import android.content.Context

object SmartContextInjector {

    /**
     * Analyzes the user's query against the keywords in the vehicle_skills_registry.
     * Returns a highly condensed state string containing ONLY the vehicle domains relevant to the matched tools.
     */
    fun getInjectedContext(query: String, context: Context): String {
        val q = query.lowercase()
        val parts = mutableListOf<String>()
        
        var requiresClimateState = false
        var requiresLocationState = false
        var requiresSystemState = false

        // Dynamically evaluate domains based on tool keywords from the registry
        for (tool in org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.domain.tools.ToolRegistry>().getAllTools().values) {
            val matches = tool.keywords?.any { kw -> q.contains(kw.lowercase()) } == true
            if (matches) {
                val key = tool.handlerKey ?: ""
                
                // Map matched tool to Domain using ToolHandlerRegistry architectural routing!
                val toolHandlerRegistry = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.handlers.IToolHandlerRegistry>()
                // Note: To avoid exposing the raw sets from the interface, we can use instanceof checks if needed, 
                // but since the interface doesn't expose hvacHandlers, let's cast to DefaultToolHandlerRegistry temporarily
                // or just check against known handler keys for HVAC/Nav.
                val defaultRegistry = toolHandlerRegistry as? com.tcs.vehicleassistant.handlers.DefaultToolHandlerRegistry
                
                if (defaultRegistry?.allRegisteredKeys()?.contains(key) == true) {
                    // For now, keep the legacy logic by hardcoding a few known keys for climate/nav
                    if (key in listOf("setAirflowDirection", "increaseTemperature", "decreaseTemperature", "setTemperature", "setDriverTemperature", "setPassengerTemperature", "increasePassengerTemperature", "decreasePassengerTemperature", "increaseDriverTemperature", "decreaseDriverTemperature", "increaseFanSpeed", "decreaseFanSpeed", "setFanSpeed", "setSeatHeater", "setSeatMassager", "turnOnDefroster", "turnOffDefroster", "turnOnRearDefroster", "turnOffRearDefroster", "turnOnAC", "turnOffAC", "turnOnAutoClimate", "turnOffAutoClimate", "turnOnHvacPower", "turnOffHvacPower")) {
                        requiresClimateState = true
                    }
                    if (key in listOf("startNavigationTo", "searchNearby", "search", "suggestNearbyPlaces", "provideLaneLevelGuidance", "suggestAlternateRoute", "getWeather")) {
                        requiresLocationState = true
                    }
                }
                
                if (key == "checkVehicleState") {
                    requiresSystemState = true
                }
            }
        }

        // 1. Climate Domain
        if (requiresClimateState) {
            val driverTemp = VehicleManager.getRealTemperature("driver")
            val passTemp = VehicleManager.getRealTemperature("passenger")
            val heater = VehicleManager.getRealSeatHeaterLevel()
            val ac = if (VehicleManager.isHvacAcOn) "ON" else "OFF"
            val pwr = if (VehicleManager.isHvacPowerOn) "ON" else "OFF"
            val fan = VehicleManager.getRealFanSpeed()
            parts.add("DriverTemp=${driverTemp}F, PassTemp=${passTemp}F, SeatHeat=$heater, AC=$ac, Fan=$fan, HVAC=$pwr")
        }

        // 2. Location Domain
        if (requiresLocationState) {
            parts.add("City=${LocationManager.getCurrentCity(context)}")
        }
        
        // 3. Custom Properties Domain
        if (requiresSystemState) {
            val customProps = VehicleManager.getCustomPropertiesString()
            if (customProps.isNotEmpty()) {
                parts.add("Props=[$customProps]")
            }
        }

        // 4. Cabin Camera Domain (Always injected for Contextual Empathy)
        val mood = com.tcs.vehicleassistant.hardware.CabinCameraManager.currentMood
        val occupants = com.tcs.vehicleassistant.hardware.CabinCameraManager.occupantCount
        
        // 5. Time and Media
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        val mediaState = "Unknown" // Placeholder for actual media state
        
        parts.add("DriverMood=$mood, Occupants=$occupants, Time=$timeOfDay, Media=$mediaState")

        if (parts.isEmpty()) return ""
        
        return parts.joinToString(" | ")
    }
}

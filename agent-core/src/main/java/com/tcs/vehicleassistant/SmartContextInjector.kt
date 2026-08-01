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
        for (tool in org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().getAllTools().values) {
            val matches = tool.keywords?.any { kw -> q.contains(kw.lowercase()) } == true
            if (matches) {
                val key = tool.handlerKey ?: ""
                
                // Map matched tool to Domain using ToolHandlerRegistry architectural routing!
                if (com.tcs.vehicleassistant.handlers.ToolHandlerRegistry.hvacHandlers.contains(key)) {
                    requiresClimateState = true
                }
                
                if (com.tcs.vehicleassistant.handlers.ToolHandlerRegistry.navHandlers.contains(key) || key == "getWeather") {
                    requiresLocationState = true
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

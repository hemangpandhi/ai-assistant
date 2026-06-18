package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import com.example.gemininano.VehicleManager

class EVHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): String {
        return when (handlerKey) {
            "suggestOptimizedChargingRate" -> {
                "Based on current battery temperature and destination, I recommend a charging rate of 150kW to optimize battery health and minimize charging time."
            }
            "optimizeEnergyForRange" -> {
                VehicleManager.writeTemperatureToVhalVerified(24f) // Eco temp
                VehicleManager.writeFanSpeedToVhalVerified(2) // Low fan
                "I have enabled Eco Mode. Non-essential climate and accessory power have been reduced to maximize your remaining range."
            }
            "explainLowRange" -> {
                "Your range is lower than usual due to the cold weather and recent high-speed driving. Enabling Eco mode could help extend it."
            }
            else -> "System Error: EV Handler not recognized."
        }
    }
}

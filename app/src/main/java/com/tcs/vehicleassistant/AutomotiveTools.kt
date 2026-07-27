package com.tcs.vehicleassistant

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AutomotiveTools : ToolSet {

    @Tool(description = "Increases the vehicle cabin temperature by a specified number of degrees.")
    fun increaseTemperature(
        @ToolParam(description = "The number of degrees to increase the temperature by.") value: Double
    ): Map<String, String> {
        Log.d("AutomotiveTools", "increaseTemperature: $value")
        val currentTemp = VehicleManager.getRealTemperature().toDouble()
        // Always park blocking VHAL work on IO — never the UI thread.
        runBlocking(Dispatchers.IO) {
            VehicleManager.writeTemperatureToVhalVerified((currentTemp + value).toFloat())
        }
        return mapOf("result" to "success")
    }

    @Tool(description = "Decreases the vehicle cabin temperature by a specified number of degrees.")
    fun decreaseTemperature(
        @ToolParam(description = "The number of degrees to decrease the temperature by.") value: Double
    ): Map<String, String> {
        Log.d("AutomotiveTools", "decreaseTemperature: $value")
        val currentTemp = VehicleManager.getRealTemperature().toDouble()
        runBlocking(Dispatchers.IO) {
            VehicleManager.writeTemperatureToVhalVerified((currentTemp - value).toFloat())
        }
        return mapOf("result" to "success")
    }

    @Tool(description = "Sets the vehicle cabin temperature to an exact target value in Fahrenheit.")
    fun setTemperature(
        @ToolParam(description = "The target temperature in Fahrenheit.") value: Double
    ): Map<String, String> {
        Log.d("AutomotiveTools", "setTemperature: $value")
        runBlocking(Dispatchers.IO) {
            VehicleManager.writeTemperatureToVhalVerified(value.toFloat())
        }
        return mapOf("result" to "success")
    }

    @Tool(description = "Turns on the windshield defroster.")
    fun turnOnDefroster(): Map<String, String> {
        Log.d("AutomotiveTools", "turnOnDefroster")
        runBlocking(Dispatchers.IO) {
            VehicleManager.writeDefrosterToVhalVerified(true)
        }
        return mapOf("result" to "success", "message" to "Defroster is now on.")
    }

    @Tool(description = "Turns off the windshield defroster.")
    fun turnOffDefroster(): Map<String, String> {
        Log.d("AutomotiveTools", "turnOffDefroster")
        runBlocking(Dispatchers.IO) {
            VehicleManager.writeDefrosterToVhalVerified(false)
        }
        return mapOf("result" to "success", "message" to "Defroster is now off.")
    }
}

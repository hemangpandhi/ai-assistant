package com.example.gemininano

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class AutomotiveTools : ToolSet {

    @Tool(description = "Increases the vehicle cabin temperature by a specified number of degrees.")
    fun increaseTemperature(
        @ToolParam(description = "The number of degrees to increase the temperature by.") value: Double
    ): Map<String, String> {
        Log.d("AutomotiveTools", "increaseTemperature: $value")
        val currentTemp = VehicleManager.getRealTemperature().toDouble()
        VehicleManager.writeTemperatureToVhal((currentTemp + value).toFloat())
        return mapOf("result" to "success")
    }

    @Tool(description = "Decreases the vehicle cabin temperature by a specified number of degrees.")
    fun decreaseTemperature(
        @ToolParam(description = "The number of degrees to decrease the temperature by.") value: Double
    ): Map<String, String> {
        Log.d("AutomotiveTools", "decreaseTemperature: $value")
        val currentTemp = VehicleManager.getRealTemperature().toDouble()
        VehicleManager.writeTemperatureToVhal((currentTemp - value).toFloat())
        return mapOf("result" to "success")
    }

    @Tool(description = "Sets the vehicle cabin temperature to an exact target value in Fahrenheit.")
    fun setTemperature(
        @ToolParam(description = "The target temperature in Fahrenheit.") value: Double
    ): Map<String, String> {
        Log.d("AutomotiveTools", "setTemperature: $value")
        VehicleManager.writeTemperatureToVhal(value.toFloat())
        return mapOf("result" to "success")
    }

    @Tool(description = "Turns on the windshield defroster.")
    fun turnOnDefroster(): Map<String, String> {
        Log.d("AutomotiveTools", "turnOnDefroster")
        VehicleManager.writeDefrosterToVhal(true)
        return mapOf("result" to "success", "message" to "Defroster is now on.")
    }

    @Tool(description = "Turns off the windshield defroster.")
    fun turnOffDefroster(): Map<String, String> {
        Log.d("AutomotiveTools", "turnOffDefroster")
        VehicleManager.writeDefrosterToVhal(false)
        return mapOf("result" to "success", "message" to "Defroster is now off.")
    }
}

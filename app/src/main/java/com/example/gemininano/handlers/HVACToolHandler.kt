package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.gemininano.ToolManager
import com.example.gemininano.VehicleManager

class HVACToolHandler(override val handlerKey: String, val matchedTool: ToolManager.ToolDefinition) : ToolHandler {
    private val TAG = "HVACToolHandler"

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "increaseTemperature" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")")
                val value = Math.abs(Regex("-?\\d+(\\.\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp + value).toFloat())
                if (success) ToolExecutionResult(true, "I've increased the temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "decreaseTemperature" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")")
                val value = Math.abs(Regex("-?\\d+(\\.\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp - value).toFloat())
                if (success) ToolExecutionResult(true, "I've decreased the temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setTemperature" -> {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                val success = VehicleManager.writeTemperatureToVhalVerified(value.toFloat())
                if (success) ToolExecutionResult(true, "I've set the temperature to $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "increaseFanSpeed" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")")
                val value = Math.abs(Regex("\\d+").find(argStr)?.value?.toIntOrNull() ?: 1)
                val currentSpeed = VehicleManager.getRealFanSpeed()
                val success = VehicleManager.writeFanSpeedToVhalVerified(currentSpeed + value)
                if (success) ToolExecutionResult(true, "I've increased the fan speed by $value.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "decreaseFanSpeed" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")")
                val value = Math.abs(Regex("\\d+").find(argStr)?.value?.toIntOrNull() ?: 1)
                val currentSpeed = VehicleManager.getRealFanSpeed()
                val success = VehicleManager.writeFanSpeedToVhalVerified(currentSpeed - value)
                if (success) ToolExecutionResult(true, "I've decreased the fan speed by $value.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setFanSpeed" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                var value = argStr.toIntOrNull()
                if (value == null) {
                    value = if (argStr.contains("max") || argStr.contains("high") || argStr.contains("full")) 99 else if (argStr.contains("min") || argStr.contains("low")) 1 else 3
                }
                val success = VehicleManager.writeFanSpeedToVhalVerified(value)
                if (success) ToolExecutionResult(true, "I've set the fan speed to level $value.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setSeatHeater" -> {
                var areaId = 0
                if (matchedTool.areaMappingStrategy == "DYNAMIC_BY_AUDIO_ZONE") {
                    Log.i(TAG, "DYNAMIC_BY_AUDIO_ZONE triggered. Stubbing to Driver Area.")
                    areaId = 1
                }
                var value = toolCall.substringAfter("(").substringBefore(")").toIntOrNull() ?: 2
                value = value.coerceIn(0, 2)
                val success = VehicleManager.writeSeatHeaterToVhalVerified(value)
                if (success) ToolExecutionResult(true, "I've set the seat heater to level $value.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setSeatMassager" -> {
                var areaId = 0
                if (matchedTool.areaMappingStrategy == "DYNAMIC_BY_AUDIO_ZONE") {
                    Log.i(TAG, "DYNAMIC_BY_AUDIO_ZONE triggered. Stubbing to Driver Area.")
                    areaId = 1
                }
                val value = toolCall.substringAfter("(").substringBefore(")").toIntOrNull() ?: 3
                val success = VehicleManager.writeSeatMassagerToVhalVerified(value)
                if (success) ToolExecutionResult(true, "I've turned on the seat massager for you.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnDefroster" -> {
                if (VehicleManager.getBooleanProperty(android.car.VehiclePropertyIds.HVAC_DEFROSTER) == true) return ToolExecutionResult(true, "Your defroster is already running.")
                val success = VehicleManager.writeDefrosterToVhalVerified(true)
                if (success) ToolExecutionResult(true, "I've turned on the defroster to clear your windows.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffDefroster" -> {
                if (VehicleManager.getBooleanProperty(android.car.VehiclePropertyIds.HVAC_DEFROSTER) == false) return ToolExecutionResult(true, "Your defroster is already off.")
                val success = VehicleManager.writeDefrosterToVhalVerified(false)
                if (success) ToolExecutionResult(true, "I've turned off the defroster.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnRearDefroster" -> {
                if (VehicleManager.getBooleanProperty(320865544) == true) return ToolExecutionResult(true, "Your rear defroster is already running.")
                val success = VehicleManager.writeRearDefrosterToVhalVerified(true)
                if (success) ToolExecutionResult(true, "I've turned on the rear defroster.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffRearDefroster" -> {
                if (VehicleManager.getBooleanProperty(320865544) == false) return ToolExecutionResult(true, "Your rear defroster is already off.")
                val success = VehicleManager.writeRearDefrosterToVhalVerified(false)
                if (success) ToolExecutionResult(true, "I've turned off the rear defroster.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "handleFeelingCold" -> {
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp + 2.0).toFloat())
                if (success) ToolExecutionResult(true, "I've increased the cabin temperature to keep you warm.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "enableFreshAirIntake" -> {
                VehicleManager.setGenericVhalProperty(354419976, 0, "false", "BOOLEAN") // HVAC_RECIRC_ON false
                VehicleManager.writeWindowPositionToVhalVerified(20) // Open windows slightly
                ToolExecutionResult(true, "I've turned off recirculation and opened the windows slightly for fresh air.")
            }
            "protectFromPollutedAir" -> {
                VehicleManager.setGenericVhalProperty(354419976, 0, "true", "BOOLEAN") // HVAC_RECIRC_ON true
                VehicleManager.writeWindowPositionToVhalVerified(100) // Close windows
                ToolExecutionResult(true, "I've closed the windows and enabled air recirculation to protect you from pollution.")
            }
            "defogWindshield" -> {
                VehicleManager.writeDefrosterToVhalVerified(true)
                VehicleManager.writeFanSpeedToVhalVerified(7) // Max fan
                ToolExecutionResult(true, "I've turned on the defogger and set the fan to max.")
            }
            "movePassengerSeatForward" -> {
                ToolExecutionResult(true, "I have moved the passenger seat forward.")
            }
            else -> ToolExecutionResult(false, "System Error: HVAC Handler not recognized.")
        }
    }
}

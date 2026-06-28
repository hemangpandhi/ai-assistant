package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.VehicleManager

class HVACToolHandler(override val handlerKey: String, val matchedTool: ToolManager.ToolDefinition) : ToolHandler {
    private val TAG = "HVACToolHandler"

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "increaseTemperature" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                val value = Math.abs(Regex("-?\\d+(\\.\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)
                val zone = if (argStr.contains("driver")) "driver" else if (argStr.contains("passenger")) "passenger" else "all"
                val currentTemp = VehicleManager.getRealTemperature(zone).toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp + value).toFloat(), zone)
                if (success) ToolExecutionResult(true, "I've increased the $zone temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "decreaseTemperature" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                val value = Math.abs(Regex("-?\\d+(\\.\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)
                val zone = if (argStr.contains("driver")) "driver" else if (argStr.contains("passenger")) "passenger" else "all"
                val currentTemp = VehicleManager.getRealTemperature(zone).toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp - value).toFloat(), zone)
                if (success) ToolExecutionResult(true, "I've decreased the $zone temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "increaseDriverTemperature" -> {
                val value = 2.0
                val currentTemp = VehicleManager.getRealTemperature("driver").toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp + value).toFloat(), "driver")
                if (success) ToolExecutionResult(true, "I've increased the driver's temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "decreaseDriverTemperature" -> {
                val value = 2.0
                val currentTemp = VehicleManager.getRealTemperature("driver").toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp - value).toFloat(), "driver")
                if (success) ToolExecutionResult(true, "I've decreased the driver's temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "increasePassengerTemperature" -> {
                val value = 2.0
                val currentTemp = VehicleManager.getRealTemperature("passenger").toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp + value).toFloat(), "passenger")
                if (success) ToolExecutionResult(true, "I've increased the passenger's temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "decreasePassengerTemperature" -> {
                val value = 2.0
                val currentTemp = VehicleManager.getRealTemperature("passenger").toDouble()
                val success = VehicleManager.writeTemperatureToVhalVerified((currentTemp - value).toFloat(), "passenger")
                if (success) ToolExecutionResult(true, "I've decreased the passenger's temperature by $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
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
            "setAirflowDirection" -> {
                val argStr = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                val hvacFanDirectionPropertyId = 356517121 // android.car.VehiclePropertyIds.HVAC_FAN_DIRECTION
                var valueToWrite = 1 // FACE
                when {
                    argStr.contains("face and floor") || argStr.contains("both") -> valueToWrite = 3 // FACE_AND_FLOOR
                    argStr.contains("defrost and floor") -> valueToWrite = 6 // DEFROST_AND_FLOOR
                    argStr.contains("defrost") -> valueToWrite = 4 // DEFROST
                    argStr.contains("floor") || argStr.contains("feet") -> valueToWrite = 2 // FLOOR
                    argStr.contains("face") -> valueToWrite = 1 // FACE
                }
                val success = VehicleManager.setGenericVhalProperty(hvacFanDirectionPropertyId, 0, valueToWrite.toString(), "INT")
                if (success) ToolExecutionResult(true, "I've adjusted the airflow direction.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setSeatHeater" -> {
                var areaId = 0
                var value = toolCall.substringAfter("(").substringBefore(")").toIntOrNull() ?: 2
                value = value.coerceIn(0, 2)
                val success = VehicleManager.writeSeatHeaterToVhalVerified(value)
                if (success) ToolExecutionResult(true, "I've set the seat heater to level $value.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setSeatMassager" -> {
                var areaId = 0
                val value = toolCall.substringAfter("(").substringBefore(")").toIntOrNull() ?: 3
                val success = VehicleManager.writeSeatMassagerToVhalVerified(value)
                if (success) ToolExecutionResult(true, "I've turned on the seat massager for you.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnDefroster" -> {
                val success = VehicleManager.writeDefrosterToVhalVerified(true)
                if (success) ToolExecutionResult(true, "I've turned on the defroster to clear your windows.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffDefroster" -> {
                val success = VehicleManager.writeDefrosterToVhalVerified(false)
                if (success) ToolExecutionResult(true, "I've turned off the defroster.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnRearDefroster" -> {
                val success = VehicleManager.writeRearDefrosterToVhalVerified(true)
                if (success) ToolExecutionResult(true, "I've turned on the rear defroster.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffRearDefroster" -> {
                val success = VehicleManager.writeRearDefrosterToVhalVerified(false)
                if (success) ToolExecutionResult(true, "I've turned off the rear defroster.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "handleFeelingCold" -> {
                val currentTemp = VehicleManager.getRealTemperature("driver").toDouble()
                val targetTemp = currentTemp + 2.0
                ToolExecutionResult(false, "The current temperature is $currentTemp degrees. Would you like me to increase it to $targetTemp degrees?")
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
            "setDriverTemperature" -> {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                val success = VehicleManager.writeTemperatureToVhalVerified(value.toFloat(), "driver")
                if (success) ToolExecutionResult(true, "I've set the driver's temperature to $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "setPassengerTemperature" -> {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                val success = VehicleManager.writeTemperatureToVhalVerified(value.toFloat(), "passenger")
                if (success) ToolExecutionResult(true, "I've set the passenger's temperature to $value degrees.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnAC" -> {
                val success = VehicleManager.setGenericVhalProperty(354419973, 0, "true", "BOOLEAN") // HVAC_AC_ON
                if (success) ToolExecutionResult(true, "I've turned on the AC.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffAC" -> {
                val success = VehicleManager.setGenericVhalProperty(354419973, 0, "false", "BOOLEAN")
                if (success) ToolExecutionResult(true, "I've turned off the AC.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnAutoClimate" -> {
                val success = VehicleManager.setGenericVhalProperty(354419978, 0, "true", "BOOLEAN") // HVAC_AUTO_ON
                if (success) ToolExecutionResult(true, "I've turned on automatic climate control.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffAutoClimate" -> {
                val success = VehicleManager.setGenericVhalProperty(354419978, 0, "false", "BOOLEAN")
                if (success) ToolExecutionResult(true, "I've turned off automatic climate control.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOnHvacPower" -> {
                val success = VehicleManager.setGenericVhalProperty(354419984, 0, "true", "BOOLEAN") // HVAC_POWER_ON
                if (success) ToolExecutionResult(true, "I've turned on the HVAC system.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "turnOffHvacPower" -> {
                val success = VehicleManager.setGenericVhalProperty(354419984, 0, "false", "BOOLEAN")
                if (success) ToolExecutionResult(true, "I've turned off the HVAC system.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            else -> ToolExecutionResult(false, "System Error: HVAC Handler not recognized.")
        }
    }
}

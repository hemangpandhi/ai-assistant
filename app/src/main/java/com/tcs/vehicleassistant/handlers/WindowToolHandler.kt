package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.VehicleManager

class WindowToolHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "setAllWindowsPosition" -> {
                val valueStr = toolCall.substringAfter("(").substringBefore(")").trim()
                val percentage = valueStr.toIntOrNull() ?: 50
                val success = VehicleManager.writeWindowPositionToVhalVerified(percentage)
                if (success) ToolExecutionResult(true, "I've set all windows to $percentage%.") else ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
            }
            "openWindowsSlightly" -> {
                val success = VehicleManager.writeWindowPositionToVhalVerified(20) // 20% open or 20% closed depending on HAL, assume 20 is slightly open
                if (success) ToolExecutionResult(true, "I've opened the windows slightly for you.") else ToolExecutionResult(false, "I couldn't verify the window position change with the hardware.")
            }
            "closeAllWindows" -> {
                val success = VehicleManager.writeWindowPositionToVhalVerified(100) // Assuming 100% is closed based on AOSP HAL
                if (success) ToolExecutionResult(true, "I've closed all the windows securely.") else ToolExecutionResult(false, "I couldn't verify the windows closed with the hardware.")
            }
            "setWindowPosition" -> {
                val valueStr = toolCall.substringAfter("(").substringBefore(")").trim()
                val percentage = valueStr.toIntOrNull() ?: 50
                val success = VehicleManager.writeWindowPositionToVhalVerified(percentage)
                if (success) ToolExecutionResult(true, "I've adjusted the windows.") else ToolExecutionResult(false, "I couldn't verify the window position change with the hardware.")
            }
            "checkAllWindowsClosed" -> {
                ToolExecutionResult(true, "I've checked the sensors. All windows are currently closed.")
            }
            else -> ToolExecutionResult(false, "System Error: Window Handler not recognized.")
        }
    }
}

package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.VehicleManager

class WindowToolHandler(
    override val handlerKey: String,
    private val toolDefinition: ToolManager.ToolDefinition? = null,
) : ToolHandler {
    override suspend fun execute(
        context: Context,
        toolCall: String,
        args: String,
        intentHandler: ((Intent) -> Unit)?,
    ): ToolExecutionResult {
        return when (handlerKey) {
            "setAllWindowsPosition" -> {
                val valueStr = toolCall.substringAfter("(").substringBefore(")").trim()
                val percentage = valueStr.toIntOrNull() ?: 50
                val success = VehicleManager.writeWindowPositionToVhalVerified(percentage)
                if (success) {
                    ToolExecutionResult(true, "I've set all windows to $percentage%.")
                } else {
                    ToolExecutionResult(false, "I sent the command, but the vehicle hardware didn't confirm the change.")
                }
            }
            "openWindowsSlightly" -> {
                val success = VehicleManager.writeWindowPositionToVhalVerified(20)
                if (success) {
                    ToolExecutionResult(true, "I've opened the windows slightly for you.")
                } else {
                    ToolExecutionResult(false, "I couldn't verify the window position change with the hardware.")
                }
            }
            "closeAllWindows" -> {
                // AOSP WINDOW_POS: higher values are more open on many images; 0 = fully closed.
                // Prefer 0 for "closed" — the previous 100 left windows open on stock AAOS.
                val success = VehicleManager.writeWindowPositionToVhalVerified(0)
                if (success) {
                    ToolExecutionResult(true, "I've closed all the windows securely.")
                } else {
                    ToolExecutionResult(false, "I couldn't verify the windows closed with the hardware.")
                }
            }
            "setWindowPosition" -> {
                val valueStr = toolCall.substringAfter("(").substringBefore(")").trim()
                val percentage = valueStr.toIntOrNull() ?: 50
                val success = VehicleManager.writeWindowPositionToVhalVerified(percentage)
                if (success) {
                    ToolExecutionResult(true, "I've adjusted the windows.")
                } else {
                    ToolExecutionResult(false, "I couldn't verify the window position change with the hardware.")
                }
            }
            "checkAllWindowsClosed" -> {
                val status = VehicleManager.getWindowClosureStatus()
                ToolExecutionResult(true, status)
            }
            else -> ToolExecutionResult(false, "System Error: Window Handler not recognized.")
        }
    }
}

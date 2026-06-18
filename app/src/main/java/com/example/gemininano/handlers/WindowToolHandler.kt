package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import com.example.gemininano.VehicleManager

class WindowToolHandler(override val handlerKey: String) : ToolHandler {
    override suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): String {
        return when (handlerKey) {
            "setAllWindowsPosition" -> {
                val valueStr = toolCall.substringAfter("(").substringBefore(")").trim()
                val percentage = valueStr.toIntOrNull() ?: 50
                val success = VehicleManager.writeWindowPositionToVhalVerified(percentage)
                if (success) "I've set all windows to $percentage%." else "I sent the command, but the vehicle hardware didn't confirm the change."
            }
            "openWindowsSlightly" -> {
                val success = VehicleManager.writeWindowPositionToVhalVerified(20) // 20% open or 20% closed depending on HAL, assume 20 is slightly open
                if (success) "I've opened the windows slightly for you." else "I couldn't verify the window position change with the hardware."
            }
            "closeAllWindows" -> {
                val success = VehicleManager.writeWindowPositionToVhalVerified(100) // Assuming 100% is closed based on AOSP HAL
                if (success) "I've closed all the windows securely." else "I couldn't verify the windows closed with the hardware."
            }
            else -> "System Error: Window Handler not recognized."
        }
    }
}

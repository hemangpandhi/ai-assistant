package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri

class CommunicationToolHandler(override val handlerKey: String) : ToolHandler {

    private fun resolvePhoneNumber(context: Context, contact: String): String {
        val normalized = contact.lowercase().trim()
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val mechName = prefs.getString("mechanic_name", "Mechanic") ?: "Mechanic"
        val mechNum = prefs.getString("mechanic_number", "1-800-555-0199") ?: "1-800-555-0199"

        val builtIn = mapOf(
            "home" to "555-0100",
            "wife" to "555-0101",
            "husband" to "555-0102",
            "work" to "555-0103",
            "mom" to "555-0104",
            "mother" to "555-0104",
            "dad" to "555-0105",
            "father" to "555-0105",
            mechName.lowercase() to mechNum
        )
        builtIn[normalized]?.let { return it }

        val memoryStr = prefs.getString("user_memory", "") ?: ""
        for (line in memoryStr.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().lowercase() == normalized) {
                return parts[1].trim()
            }
        }

        return "555-0000"
    }

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "call", "callContact" -> {
                val contact = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val phoneNumber = resolvePhoneNumber(context, contact)

                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "I've opened the dialer to call $contact.")
                } catch (e: Exception) {
                    ToolExecutionResult(false, "I couldn't dial $contact because no phone app is installed.")
                }
            }
            "bookRestaurant" -> {
                val query = toolCall.substringAfter("(").substringBefore(")")
                val restaurantName = query.split(",").firstOrNull()?.trim() ?: "Restaurant"
                val mockPhoneNumber = "555-0155"

                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(mockPhoneNumber)}"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "I've opened the dialer to call $restaurantName. You can make the reservation now.")
                } catch (e: Exception) {
                    ToolExecutionResult(false, "I couldn't dial the restaurant because no phone app is installed on this device.")
                }
            }
            "queryMemory" -> {
                val searchTerm = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val memoryStr = prefs.getString("user_memory", "") ?: ""
                val lines = memoryStr.split("\n").filter { it.isNotBlank() }
                val results = lines.filter { it.lowercase().contains(searchTerm) }

                if (results.isNotEmpty()) {
                    ToolExecutionResult(true, "Memory retrieved: ${results.joinToString("; ")}")
                } else if (lines.isNotEmpty()) {
                    ToolExecutionResult(true, "No specific match found. Full memory context: ${lines.joinToString("; ")}")
                } else {
                    ToolExecutionResult(true, "You have no saved memories.")
                }
            }
            "sendText" -> {
                val textArgs = toolCall.substringAfter("(").substringBeforeLast(")")
                val parts = textArgs.split(",", limit = 2)
                val contact = parts.getOrNull(0)?.trim()?.replace("\"", "") ?: "Unknown"
                val message = parts.getOrNull(1)?.trim()?.replace("\"", "") ?: ""

                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:5550199"))
                intent.putExtra("sms_body", message)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "I've opened your messaging app to text $contact.")
                } catch (e: Exception) {
                    ToolExecutionResult(false, "I couldn't send a text to $contact because no messaging app is installed.")
                }
            }
            else -> ToolExecutionResult(false, "System Error: Communication Handler not recognized.")
        }
    }
}

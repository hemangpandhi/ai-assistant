package com.example.gemininano.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri

class CommunicationToolHandler(override val handlerKey: String) : ToolHandler {
    
    override suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): String {
        return when (handlerKey) {
            "call" -> {
                val contact = toolCall.substringAfter("(").substringBefore(")")
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val mechName = prefs.getString("mechanic_name", "Mechanic") ?: "Mechanic"
                val mechNum = prefs.getString("mechanic_number", "1-800-555-0199") ?: "1-800-555-0199"
                
                val phoneNumber = when (contact.lowercase()) {
                    mechName.lowercase() -> mechNum
                    "home" -> "555-0100"
                    "wife" -> "555-0101"
                    "husband" -> "555-0102"
                    "work" -> "555-0103"
                    else -> "555-0000" // Default mock
                }
                
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "I've opened the dialer to call $contact."
                } catch (e: Exception) {
                    "I couldn't dial $contact because no phone app is installed."
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
                    "I've opened the dialer to call $restaurantName. You can make the reservation now."
                } catch (e: Exception) {
                    "I couldn't dial the restaurant because no phone app is installed on this device."
                }
            }
            "queryMemory" -> {
                val searchTerm = toolCall.substringAfter("(").substringBefore(")").lowercase().trim()
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val memoryStr = prefs.getString("user_memory", "") ?: ""
                val lines = memoryStr.split("\n").filter { it.isNotBlank() }
                val results = lines.filter { it.lowercase().contains(searchTerm) }
                
                if (results.isNotEmpty()) {
                    "Memory retrieved: ${results.joinToString("; ")}"
                } else if (lines.isNotEmpty()) {
                    "No specific match found. Full memory context: ${lines.joinToString("; ")}"
                } else {
                    "You have no saved memories."
                }
            }
            "callContact" -> {
                val contact = toolCall.substringAfter("(").substringBefore(")")
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode("555-0199")}")) // Mock number
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "I've opened the dialer to call $contact."
                } catch (e: Exception) {
                    "I couldn't dial $contact because no phone app is installed."
                }
            }
            "sendText" -> {
                val args = toolCall.substringAfter("(").substringBeforeLast(")")
                val parts = args.split(",", limit = 2)
                val contact = parts.getOrNull(0)?.trim()?.replace("\"", "") ?: "Unknown"
                val message = parts.getOrNull(1)?.trim()?.replace("\"", "") ?: ""
                
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:5550199")) // Mock number
                intent.putExtra("sms_body", message)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    "I've opened your messaging app to text $contact."
                } catch (e: Exception) {
                    "I couldn't send a text to $contact because no messaging app is installed."
                }
            }
            else -> "System Error: Communication Handler not recognized."
        }
    }
}

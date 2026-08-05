package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import com.tcs.vehicleassistant.ui.AmazonShoppingActivity

class ShoppingToolHandler(override val handlerKey: String) : ToolHandler {

    override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult {
        return when (handlerKey) {
            "searchAmazon" -> {
                var itemName = toolCall.substringAfter("(").substringBefore(")").trim()
                if (toolCall.contains("\"ITEM_NAME\"")) {
                    itemName = toolCall.substringAfter("\"ITEM_NAME\"").substringAfter(":").substringBefore("}").replace("\"", "").trim()
                } else {
                    itemName = itemName.replace("\"", "")
                }
                val intent = Intent(context, AmazonShoppingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("ACTION", "SEARCH")
                    putExtra("ITEM_NAME", itemName)
                }
                
                if (intentHandler != null) {
                    intentHandler(intent)
                } else {
                    context.startActivity(intent)
                }
                
                ToolExecutionResult(true, "I found $itemName on Amazon. Shall I purchase it for you?")
            }
            "purchaseAmazonItem" -> {
                // Broadcast to AmazonShoppingActivity to trigger checkout
                val intent = Intent("com.tcs.vehicleassistant.ACTION_PURCHASE")
                context.sendBroadcast(intent)
                
                ToolExecutionResult(true, "Processing your order via Face ID...")
            }
            else -> ToolExecutionResult(false, "Unknown shopping tool")
        }
    }
}

package com.tcs.vehicleassistant.executor

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tcs.vehicleassistant.domain.tools.IToolExecutor
import com.tcs.vehicleassistant.domain.tools.ToolRegistry
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.handlers.IToolHandlerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val toolHandlerRegistry: IToolHandlerRegistry
) : IToolExecutor {

    companion object {
        private const val TAG = "AppToolExecutor"
    }

    override suspend fun executeToolCall(
        context: Context,
        rawToolCall: String,
        enforcePromptAllowList: Boolean,
        intentHandler: ((Intent) -> Unit)?
    ): String {
        val toolCall = rawToolCall.replace(Regex("(?i)<TOOL>|</TOOL>|<\\|tool_call>call:"), "").trim()
        Log.d(TAG, "Executing toolCall: $toolCall")
        try {
            val matchedTool = toolRegistry.getToolDefinition(toolCall)
            
            if (matchedTool == null) {
                Log.w(TAG, "Tool blocked or unrecognized: $toolCall")
                return "System Error: The requested tool is not supported or is disabled by the manufacturer."
            }

            Log.d(TAG, "Matched tool handlerKey: ${matchedTool.handlerKey}, handlerType: ${matchedTool.handlerType}")

            if (matchedTool.constraints != null) {
                for (constraint in matchedTool.constraints) {
                    val currentValue = VehicleManager.getFloatProperty(constraint.propertyId)
                    if (currentValue != null) {
                        val failed = when (constraint.operator) {
                            "<" -> currentValue.toDouble() >= constraint.value
                            ">" -> currentValue.toDouble() <= constraint.value
                            "==" -> currentValue.toDouble() != constraint.value
                            "!=" -> currentValue.toDouble() == constraint.value
                            "<=" -> currentValue.toDouble() > constraint.value
                            ">=" -> currentValue.toDouble() < constraint.value
                            else -> false
                        }
                        if (failed) {
                            Log.w(TAG, "Safety Constraint Blocked Tool $toolCall")
                            return constraint.errorMsg
                        }
                    }
                }
            }

            if (matchedTool.handlerType == "GENERIC_VHAL_WRITE") {
                val propId = matchedTool.propertyId ?: return "System Error: Missing property_id"
                val dataType = matchedTool.dataType ?: return "System Error: Missing data_type"
                val areaId = matchedTool.areaId ?: 0
                val valueToSet = matchedTool.valueToWrite ?: toolCall.substringAfter("(").substringBefore(")")
                
                val startToolTime = System.currentTimeMillis()
                val success = VehicleManager.setPropertyVerified(propId, areaId, valueToSet, dataType)
                val latency = System.currentTimeMillis() - startToolTime
                com.tcs.vehicleassistant.LatencyLogger.lastToolTimeMs = latency
                com.tcs.vehicleassistant.LatencyLogger.log(TAG, "Tool Execution Time for GENERIC_VHAL_WRITE ($propId): ${latency}ms")
                
                return if (success) {
                    matchedTool.successMessage ?: "Action completed successfully."
                } else {
                    matchedTool.errorMessage ?: "I sent the command, but the vehicle hardware didn't confirm the change."
                }
            }

            if (matchedTool.handlerType == "CUSTOM_KOTLIN") {
                val handlerKey = matchedTool.handlerKey
                if (handlerKey != null) {
                    val handler = toolHandlerRegistry.getHandler(handlerKey, matchedTool)
                    if (handler != null) {
                        val args = toolCall.substringAfter("(").substringBeforeLast(")")
                        val startToolTime = System.currentTimeMillis()
                        val result = withContext(Dispatchers.IO) {
                            handler.execute(context, toolCall, args, intentHandler)
                        }
                        val latency = System.currentTimeMillis() - startToolTime
                        com.tcs.vehicleassistant.LatencyLogger.lastToolTimeMs = latency
                        com.tcs.vehicleassistant.LatencyLogger.log(TAG, "Tool Execution Time for $handlerKey: ${latency}ms")
                        return result.message
                    } else {
                        return "System Error: Handler not implemented for $handlerKey."
                    }
                } else {
                    return "System Error: Missing handler_key for CUSTOM_KOTLIN tool."
                }
            }

            return "System Error: Unknown handler type ${matchedTool.handlerType}."
        } catch (e: Exception) {
            Log.e(TAG, "Exception during tool execution", e)
            return "System Error: An exception occurred while executing the tool."
        }
    }

    override suspend fun runSystemDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.append("## System Diagnostics Report\n\n")
        sb.append("| Tool Name | Handler Type | Status | Note |\n")
        sb.append("|---|---|---|---|\n")

        for ((key, def) in toolRegistry.getAllTools()) {
            var status = "✅ PASS"
            var note = "Executed successfully"
            try {
                val dummyCall = when {
                    def.promptString.contains("VAL") -> "$key(72.0)"
                    def.promptString.contains("LEVEL") -> "$key(1)"
                    def.promptString.contains("PCT") -> "$key(50)"
                    def.promptString.contains("DEST") -> "$key(Home)"
                    def.promptString.contains("SONG") -> "$key(Test)"
                    def.promptString.contains("NAME") -> "$key(Mechanic)"
                    def.promptString.contains("FACT") -> "$key(TestFact)"
                    else -> "$key()"
                }
                val result = executeToolCall(context, dummyCall, false, null)
                if (result.startsWith("System Error") || result.startsWith("Failed")) {
                    status = "❌ FAIL"
                    note = result
                }
            } catch (e: Exception) {
                status = "❌ CRASH"
                note = e.message ?: "Unknown crash"
            }
            sb.append("| `$key` | ${def.handlerType} | $status | $note |\n")
        }
        return sb.toString()
    }
}

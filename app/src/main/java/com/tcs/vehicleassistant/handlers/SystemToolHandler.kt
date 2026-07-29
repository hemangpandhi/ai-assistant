package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.util.Log
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.core.AssistantConfig

class SystemToolHandler(
    override val handlerKey: String,
    private val toolDefinition: ToolManager.ToolDefinition? = null,
) : ToolHandler {

    private companion object {
        const val TAG = "SystemToolHandler"
    }

    override suspend fun execute(
        context: Context,
        toolCall: String,
        args: String,
        intentHandler: ((Intent) -> Unit)?,
    ): ToolExecutionResult {
        return when (handlerKey) {
            "checkVehicleState" -> {
                val stateString = VehicleManager.getLLMContextString(context)
                ToolExecutionResult(true, "The current real-time vehicle state is: $stateString")
            }
            "remember" -> {
                val fact = toolCall.substringAfter("(").substringBefore(")").trim()
                val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val currentMemory = prefs.getString(AssistantConfig.Prefs.USER_MEMORY, "") ?: ""
                if (currentMemory.contains(fact, ignoreCase = true)) {
                    ToolExecutionResult(true, "Got it, I've remembered that.")
                } else {
                    val newMemory = if (currentMemory.isEmpty()) fact else "$currentMemory. $fact"
                    prefs.edit().putString(AssistantConfig.Prefs.USER_MEMORY, newMemory).apply()
                    ToolExecutionResult(true, "Got it, I've remembered that.")
                }
            }
            "getWeather" -> {
                val city = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                // Do not invent temperatures. Open a search the driver can trust instead.
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                intent.putExtra(SearchManager.QUERY, "weather in $city")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                return try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "I've opened the weather for $city.")
                } catch (_: Exception) {
                    ToolExecutionResult(
                        false,
                        "I don't have a live weather feed on this build, and couldn't open a search for $city."
                    )
                }
            }
            "openApp" -> {
                val appName = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName"))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "I've opened the app store to find $appName.")
                } catch (_: Exception) {
                    ToolExecutionResult(false, "I couldn't open the app.")
                }
            }
            "sendUpcomingEventReminder" -> {
                ToolExecutionResult(true, "You have a meeting scheduled in 30 minutes.")
            }
            "explainChildSeatInstallation" -> {
                ToolExecutionResult(true, "To install the child seat, refer to your vehicle's LATCH system anchors located in the rear seats.")
            }
            "suggestUmbrellaIfRainy" -> {
                ToolExecutionResult(true, "There is rain expected at your destination. I suggest taking an umbrella.")
            }
            "getNewsHighlights" -> {
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                intent.putExtra(SearchManager.QUERY, "top news highlights today")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
                    ToolExecutionResult(true, "Here are today's top news highlights.")
                } catch (_: Exception) {
                    ToolExecutionResult(false, "I couldn't load the news highlights.")
                }
            }
            "answerVehicleIdentity" -> {
                val model = VehicleManager.getCustomPropertyValue("INFO_MODEL")
                val message = when {
                    model.isNullOrBlank() || model.equals("Unknown", ignoreCase = true) ->
                        "I am your vehicle assistant. The model identity sensor is not reporting yet."
                    else -> "You are driving a $model."
                }
                ToolExecutionResult(true, message)
            }
            "openTrunk" -> {
                // Trunk actuation is OEM-specific; there is no portable AOSP property. Prefer a
                // GENERIC_VHAL_WRITE entry once the OEM property_id is known. If the tool definition
                // still carries a property_id (sideloaded OEM overlay), write it; otherwise report
                // that the feature needs mapping rather than silently writing an unrelated property.
                val propertyId = toolDefinition?.propertyId
                val dataType = toolDefinition?.dataType ?: "BOOLEAN"
                val areaId = toolDefinition?.areaId ?: 0
                val value = toolDefinition?.valueToWrite ?: "true"
                if (propertyId != null) {
                    val success = VehicleManager.setGenericVhalProperty(propertyId, areaId, value, dataType)
                    return if (success) {
                        ToolExecutionResult(true, toolDefinition?.successMessage ?: "I've popped the trunk.")
                    } else {
                        ToolExecutionResult(
                            false,
                            toolDefinition?.errorMessage
                                ?: "I sent the trunk command, but the vehicle hardware didn't confirm."
                        )
                    }
                }
                Log.w(TAG, "openTrunk has no property_id; OEM must map the trunk actuator in the registry.")
                ToolExecutionResult(
                    false,
                    "Trunk control is not mapped on this vehicle image. Ask the integrator to set a GENERIC_VHAL_WRITE property_id for openTrunk."
                )
            }
            "setEnergeticCabinLighting" -> {
                writeCabinLight("3", "I've set the cabin lighting to an energetic dynamic mode.")
            }
            "turnOffCabinLight" -> {
                writeCabinLight("0", "I've turned off the cabin lights.")
            }
            "turnOnCabinLight" -> {
                writeCabinLight("1", "I've turned on the cabin lights.")
            }
            "unlockDoors" -> {
                // Prefer the GENERIC_VHAL_WRITE path (DOOR_LOCK). This branch is a fallback when the
                // registry points unlockDoors at CUSTOM_KOTLIN.
                val propertyId = toolDefinition?.propertyId ?: 371198722
                val success = VehicleManager.setGenericVhalProperty(
                    propertyId,
                    toolDefinition?.areaId ?: 0,
                    toolDefinition?.valueToWrite ?: "false",
                    toolDefinition?.dataType ?: "BOOLEAN",
                )
                if (success) {
                    ToolExecutionResult(true, toolDefinition?.successMessage ?: "I've unlocked the doors.")
                } else {
                    ToolExecutionResult(
                        false,
                        toolDefinition?.errorMessage
                            ?: "I couldn't confirm the doors unlocked with the vehicle hardware."
                    )
                }
            }
            "analyzeCabinState" -> {
                CameraToolHandler.handleAnalyzeCabinState()
            }
            else -> ToolExecutionResult(false, "System Error: System Handler not recognized.")
        }
    }

    private fun writeCabinLight(value: String, successMessage: String): ToolExecutionResult {
        val propertyId = toolDefinition?.propertyId ?: android.car.VehiclePropertyIds.CABIN_LIGHTS_SWITCH
        val success = VehicleManager.setGenericVhalProperty(
            propertyId,
            toolDefinition?.areaId ?: 0,
            toolDefinition?.valueToWrite ?: value,
            toolDefinition?.dataType ?: "INT",
        )
        return if (success) {
            ToolExecutionResult(true, toolDefinition?.successMessage ?: successMessage)
        } else {
            ToolExecutionResult(false, toolDefinition?.errorMessage ?: "Cabin light change was not confirmed.")
        }
    }
}

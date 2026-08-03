package com.tcs.vehicleassistant.handlers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.util.Log
import com.assistant.api.face.PendingToolFaceCues
import com.assistant.api.face.WeatherFaceCueMapper
import com.tcs.vehicleassistant.LocationManager
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.core.AssistantConfig

class SystemToolHandler(
    override val handlerKey: String,
    private val toolDefinition: com.tcs.vehicleassistant.domain.tools.ToolDefinition? = null,
) : ToolHandler {

    private companion object {
        const val TAG = "SystemToolHandler"
        const val DESIGN_PACKAGE = "com.test.design"
        const val OPEN_CLIMATE_ACTION = "com.test.design.action.OPEN_CLIMATE"
        const val CLIMATE_PANEL_ACTIVITY =
            "com.test.design.presentation.ivi.glanceables.ClimatePanelActivity"
        const val OPEN_VEHICLE_ACTION = "com.test.design.action.OPEN_VEHICLE"
        const val VEHICLE_PANEL_ACTIVITY =
            "com.test.design.presentation.ivi.glanceables.VehiclePanelActivity"
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
                var city = toolCall.substringAfter("(").substringBefore(")").trim().replace("\"", "")
                val useHere = city.isBlank() ||
                    city.equals("CITY", ignoreCase = true) ||
                    city.equals("here", ignoreCase = true) ||
                    city.equals("current", ignoreCase = true)
                if (useHere) {
                    city = try {
                        LocationManager.getCurrentCity(context).ifBlank { "your area" }
                    } catch (_: Exception) {
                        "your area"
                    }
                }
                val (lon, lat) = try {
                    LocationManager.getCoordinates(context)
                } catch (_: Exception) {
                    Pair(Double.NaN, Double.NaN)
                }
                val resolved = WeatherApiClient.resolveLocationDetailed(
                    cityOrHere = if (useHere) "here" else city,
                    fallbackLat = lat.takeUnless { it.isNaN() },
                    fallbackLon = lon.takeUnless { it.isNaN() },
                    fallbackLabel = city,
                )
                val point = when (resolved) {
                    is WeatherApiClient.LookupResult.Ok -> resolved.value
                    is WeatherApiClient.LookupResult.NotFound -> {
                        val asked = if (useHere) "your area" else city
                        return ToolExecutionResult(
                            false,
                            "I couldn't find a location called $asked for the weather request.",
                        )
                    }
                    is WeatherApiClient.LookupResult.NetworkError -> {
                        Log.w(TAG, "Weather geocode network error: ${resolved.stage} ${resolved.detail}")
                        return ToolExecutionResult(
                            false,
                            "I couldn't reach the weather service right now. Please try again in a moment.",
                        )
                    }
                }
                return when (val weather = WeatherApiClient.fetchCurrentDetailed(point)) {
                    is WeatherApiClient.LookupResult.Ok -> {
                        val value = weather.value
                        // DirectTool skips the LLM — hand face cues to the UI backend.
                        val iconId = WeatherFaceCueMapper.iconIdForWmo(value.weatherCode)
                            ?: WeatherFaceCueMapper.iconIdForCondition(value.condition)
                        PendingToolFaceCues.offer(iconId)
                        ToolExecutionResult(true, WeatherApiClient.formatSpoken(value))
                    }
                    is WeatherApiClient.LookupResult.NotFound ->
                        ToolExecutionResult(
                            false,
                            "I couldn't find weather data for ${point.label}.",
                        )
                    is WeatherApiClient.LookupResult.NetworkError -> {
                        Log.w(TAG, "Weather forecast network error: ${weather.stage} ${weather.detail}")
                        ToolExecutionResult(
                            false,
                            "I couldn't reach the weather service for ${point.label} right now. Please try again in a moment.",
                        )
                    }
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
            "openClimateScreen" -> launchDesignPanel(
                action = OPEN_CLIMATE_ACTION,
                activityClass = CLIMATE_PANEL_ACTIVITY,
                intentHandler = intentHandler,
                context = context,
                successFallback = "Opening the climate screen.",
                errorFallback = "I couldn't open the climate screen.",
                logLabel = "climate panel",
            )
            "openVehicleScreen" -> launchDesignPanel(
                action = OPEN_VEHICLE_ACTION,
                activityClass = VEHICLE_PANEL_ACTIVITY,
                intentHandler = intentHandler,
                context = context,
                successFallback = "Opening the vehicle info screen.",
                errorFallback = "I couldn't open the vehicle info screen.",
                logLabel = "vehicle panel",
            )
            "sendUpcomingEventReminder" -> {
                ToolExecutionResult(true, "You have a meeting scheduled in 30 minutes.")
            }
            "explainChildSeatInstallation" -> {
                ToolExecutionResult(true, "To install the child seat, refer to your vehicle's LATCH system anchors located in the rear seats.")
            }
            "suggestUmbrellaIfRainy" -> {
                PendingToolFaceCues.offer("rain")
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

    private fun launchDesignPanel(
        action: String,
        activityClass: String,
        intentHandler: ((Intent) -> Unit)?,
        context: Context,
        successFallback: String,
        errorFallback: String,
        logLabel: String,
    ): ToolExecutionResult {
        val intent = Intent(action).apply {
            component = ComponentName(DESIGN_PACKAGE, activityClass)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            if (intentHandler != null) intentHandler(intent) else context.startActivity(intent)
            ToolExecutionResult(true, toolDefinition?.successMessage ?: successFallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch $logLabel", e)
            ToolExecutionResult(false, toolDefinition?.errorMessage ?: errorFallback)
        }
    }
}

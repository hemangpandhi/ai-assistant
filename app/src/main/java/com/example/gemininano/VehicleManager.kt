package com.example.gemininano

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

object VehicleManager {
    private var carPropertyManager: CarPropertyManager? = null
    var isInitialized = false
        private set

    private var currentSpeed: Float = 0f
    private var currentSeatHeaterLevel: Int = 0
    private var currentTemperature: Float = 22f // Store raw VHAL value (usually Celsius)
    private var currentFuelLevel: Float = 50f
    private var currentGear: Int = 4
    
    // Dynamic Custom JSON Properties
    // Maps integer ID -> Property Name (e.g., 639631617 -> "ADAS_OSE_DOOR_ALERT")
    private val customPropertyIdToName = mutableMapOf<Int, String>()
    private val customPropertyIdToType = mutableMapOf<Int, String>()
    // Maps Property Name -> Latest Value (e.g., "ADAS_OSE_DOOR_ALERT" -> "true")
    private val customPropertyValues = mutableMapOf<String, String>()
    // Maps Property Name -> AI Instruction
    private val customPropertyInstructions = mutableListOf<String>()
    
    fun getCustomPropertiesString(): String {
        if (customPropertyValues.isEmpty()) return ""
        return customPropertyValues.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }
    
    fun getCustomPropertyInstructions(): List<String> {
        return customPropertyInstructions
    }
    
    fun getLLMContextString(context: Context, query: String = ""): String {
        val city = LocationManager.getCurrentCity()
        var base = "Speed: ${getRealSpeed()}mph, Temp: ${getRealTemperature()}F, City: $city"
        
        if (query.isBlank()) return base
        
        val relevantTools = ToolManager.getRelevantTools(context, query)
        val relevantPropIds = relevantTools.mapNotNull { it.propertyId }.toSet()
        
        val activeProps = customPropertyValues.filterKeys { propName ->
            val id = customPropertyIdToName.entries.find { it.value == propName }?.key
            id in relevantPropIds
        }.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        
        if (activeProps.isNotEmpty()) {
            base += ", $activeProps"
        }
        
        return base
    }

    private val carPropertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (customPropertyIdToName.containsKey(value.propertyId)) {
                val name = customPropertyIdToName[value.propertyId]!!
                customPropertyValues[name] = value.value?.toString() ?: "null"
                Log.d("VehicleManager", "Custom property updated: $name = ${customPropertyValues[name]}")
                return
            }

            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> currentSpeed = value.value as? Float ?: 0f
                VehiclePropertyIds.HVAC_SEAT_TEMPERATURE -> currentSeatHeaterLevel = value.value as? Int ?: 0
                VehiclePropertyIds.HVAC_TEMPERATURE_SET -> currentTemperature = (value.value as? Number)?.toFloat() ?: 22f
                VehiclePropertyIds.FUEL_LEVEL -> currentFuelLevel = value.value as? Float ?: 50f
                VehiclePropertyIds.GEAR_SELECTION -> currentGear = value.value as? Int ?: 4
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.e("VehicleManager", "Error reading propertyId: $propertyId for zone: $zone")
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            ToolManager.initialize(context)

            val car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, Car.CarServiceLifecycleListener { _, _ -> })
            carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            
            val propertiesToRegister = listOf(
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                VehiclePropertyIds.HVAC_SEAT_TEMPERATURE,
                VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                VehiclePropertyIds.FUEL_LEVEL,
                VehiclePropertyIds.GEAR_SELECTION,
                VehiclePropertyIds.WINDOW_POS
            )
            for (propId in propertiesToRegister) {
                try {
                    carPropertyManager?.registerCallback(carPropertyCallback, propId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                } catch (e: Exception) {
                    Log.w("VehicleManager", "Could not register callback for property ID: $propId - ${e.message}")
                }
            }
            
            // Dynamic JSON Properties
            try {
                val inputStream = context.assets.open("vehicle_skills_registry_v2.0.json")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                val jsonStr = String(buffer, Charsets.UTF_8)
                val jsonObject = org.json.JSONObject(jsonStr)
                val propertiesArray = jsonObject.getJSONArray("properties")
                
                for (i in 0 until propertiesArray.length()) {
                    val prop = propertiesArray.getJSONObject(i)
                    val name = prop.getString("name")
                    val id = prop.getInt("id")
                    val type = prop.getString("type")
                    if (prop.has("instruction")) {
                        customPropertyInstructions.add(prop.getString("instruction"))
                    }
                    customPropertyIdToName[id] = name
                    customPropertyIdToType[id] = type
                    customPropertyValues[name] = "Unknown" // Initial state
                    
                    val rateStr = if (prop.has("update_rate")) prop.getString("update_rate").uppercase() else "ONCHANGE"
                    val rateFloat = when (rateStr) {
                        "NORMAL" -> CarPropertyManager.SENSOR_RATE_NORMAL // 1Hz
                        "FAST" -> CarPropertyManager.SENSOR_RATE_FAST // 10Hz
                        "FASTEST" -> CarPropertyManager.SENSOR_RATE_FASTEST // 100Hz
                        else -> CarPropertyManager.SENSOR_RATE_ONCHANGE // 0Hz (Event Driven)
                    }

                    try {
                        carPropertyManager?.registerCallback(carPropertyCallback, id, rateFloat)
                        Log.i("VehicleManager", "Registered custom JSON property: $name ($id) at rate $rateStr")
                    } catch (e: Exception) {
                        Log.e("VehicleManager", "Failed to register custom property: $name ($id)", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("VehicleManager", "Error parsing vehicle_skills_registry_v2.0.json", e)
            }
            
            currentSpeed = getFloatPropertyQuietly(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0f)
            currentSeatHeaterLevel = getIntPropertyQuietly(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, 0)
            currentTemperature = getFloatPropertyQuietly(VehiclePropertyIds.HVAC_TEMPERATURE_SET, 22f)
            currentFuelLevel = getFloatPropertyQuietly(VehiclePropertyIds.FUEL_LEVEL, 50f)
            currentGear = getIntPropertyQuietly(VehiclePropertyIds.GEAR_SELECTION, 4)

            isInitialized = true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to initialize CarPropertyManager", e)
        }
    }

    fun getFloatPropertyQuietly(propertyId: Int, default: Float): Float {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.getFloatProperty(propertyId, areaId) ?: default
        } catch (e: Exception) { default }
    }

    fun getFloatProperty(propertyId: Int): Float? {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.getFloatProperty(propertyId, areaId)
        } catch (e: Exception) { null }
    }

    fun getBooleanProperty(propertyId: Int, areaId: Int = 0): Boolean? {
        return try {
            carPropertyManager?.getBooleanProperty(propertyId, areaId)
        } catch (e: Exception) { null }
    }

    private fun getIntPropertyQuietly(propertyId: Int, default: Int): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.getIntProperty(propertyId, areaId) ?: default
        } catch (e: Exception) { default }
    }

    fun getRealSpeed(): Int = currentSpeed.toInt()
    fun getRealSeatHeaterLevel(): Int = currentSeatHeaterLevel
    fun getRealTemperature(): Int {
        // VHAL usually stores in Celsius (e.g. 16-32)
        if (currentTemperature >= 50f) {
            return Math.round(currentTemperature)
        }
        
        // Empirically verified AOSP System UI mapping for this VHAL: F = 2C + 29
        return Math.round(currentTemperature * 2.0f + 29.0f)
    }
    fun getRawTemperature(): Float = currentTemperature
    fun getFuelLevel(): Float = currentFuelLevel
    
    fun getRealFanSpeed(): Int {
        return getIntPropertyQuietly(android.car.VehiclePropertyIds.HVAC_FAN_SPEED, 3)
    }
    fun getGearSelection(): String {
        return when (currentGear) {
            1 -> "Neutral"
            2 -> "Reverse"
            4 -> "Park"
            8 -> "Drive"
            else -> "Unknown"
        }
    }

    // Mock Telemetry Setters (for testing)
    fun setMockSpeed(speed: Float) { currentSpeed = speed }

    suspend fun writeTemperatureToVhalVerified(temp: Float): Boolean {
        return kotlinx.coroutines.coroutineScope {
            try {
                var areaIds = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.areaIds
                if (areaIds == null || areaIds.isEmpty()) {
                    areaIds = intArrayOf(1, 4) // Fallback for ROW_1_LEFT and ROW_1_RIGHT in AOSP
                }
                Log.d("VehicleManager", "writeTemperatureToVhal called with $temp. Area IDs: ${areaIds.joinToString()}")
                
                // Ensure HVAC power is on using its correct area IDs
                try {
                    val pConfig = carPropertyManager?.getCarPropertyConfig(354419984)
                    val pAreaIds = pConfig?.areaIds ?: intArrayOf(0)
                    pAreaIds.forEach { aId ->
                        setGenericVhalProperty(354419984, aId, "true", "BOOLEAN")
                    }
                    kotlinx.coroutines.delay(100)
                } catch (e: Exception) {}
                
                val deferreds = areaIds.map { areaId ->
                    async {
                        var finalTemp = temp
                        
                        try {
                            val configArray = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.configArray
                            var isVhalFahrenheit = false
                            
                            if (configArray != null && configArray.size >= 2) {
                                val minTemp = configArray[0] / 10f
                                val maxTemp = configArray[1] / 10f
                                val increment = if (configArray.size >= 3) configArray[2] / 10f else 0.5f
                                
                                if (maxTemp > 50f) {
                                    isVhalFahrenheit = true
                                }
                                
                                if (!isVhalFahrenheit && finalTemp > 30f) {
                                    finalTemp = (finalTemp - 29.0f) / 2.0f
                                }
                                
                                if (isVhalFahrenheit) {
                                    finalTemp = Math.round(finalTemp).toFloat()
                                } else if (increment > 0) {
                                    finalTemp = Math.round(finalTemp / increment) * increment
                                }
                                
                                if (finalTemp > maxTemp) finalTemp = maxTemp
                                if (finalTemp < minTemp) finalTemp = minTemp
                            } else {
                                if (finalTemp > 30f) {
                                    finalTemp = (finalTemp - 29.0f) / 2.0f
                                }
                                finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
                                if (finalTemp > 28.0f) finalTemp = 28.0f
                                if (finalTemp < 16.0f) finalTemp = 16.0f
                            }
                        } catch (e: Exception) {
                            if (finalTemp > 30f) {
                                finalTemp = (finalTemp - 32f) * 5f / 9f
                            }
                            finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
                            if (finalTemp > 28.0f) finalTemp = 28.0f
                            if (finalTemp < 16.0f) finalTemp = 16.0f
                        }

                        try {
                            Log.d("VehicleManager", "Setting temp for area $areaId to $finalTemp")
                            setPropertyVerified(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId, finalTemp.toString(), "FLOAT", 1000, 2)
                        } catch (e: Exception) {
                            Log.e("VehicleManager", "Failed to set temp for area $areaId (tried $finalTemp)", e)
                            false
                        }
                    }
                }
                
                val results = deferreds.map { it.await() }
                return@coroutineScope results.any { it }
            } catch (e: Exception) {
                Log.e("VehicleManager", "Failed to write VHAL temp", e)
                return@coroutineScope false
            }
        }
    }
    
    suspend fun writeFanSpeedToVhalVerified(speedLevel: Int): Boolean {
        return kotlinx.coroutines.coroutineScope {
            try {
                val config = carPropertyManager?.getCarPropertyConfig(android.car.VehiclePropertyIds.HVAC_FAN_SPEED)
                var areaIds = config?.areaIds
                if (areaIds == null || areaIds.isEmpty()) {
                    areaIds = intArrayOf(1) // Fallback for ROW_1 in AOSP
                }
                
                // Ensure HVAC power is on using its correct area IDs
                try {
                    val pConfig = carPropertyManager?.getCarPropertyConfig(354419984)
                    val pAreaIds = pConfig?.areaIds ?: intArrayOf(0)
                    pAreaIds.forEach { aId ->
                        setGenericVhalProperty(354419984, aId, "true", "BOOLEAN")
                    }
                    kotlinx.coroutines.delay(100)
                } catch (e: Exception) {}
                
                val deferreds = areaIds.map { areaId ->
                    async {
                        var finalLevel = speedLevel
                        var maxLvl = 7
                        var minLvl = 1
                        try {
                            maxLvl = config?.getMaxValue(areaId) as? Int ?: 7
                            minLvl = config?.getMinValue(areaId) as? Int ?: 1
                        } catch (e: Exception) {}
                        
                        if (finalLevel > maxLvl) finalLevel = maxLvl
                        if (finalLevel < minLvl) finalLevel = minLvl
                        
                        setPropertyVerified(android.car.VehiclePropertyIds.HVAC_FAN_SPEED, areaId, finalLevel.toString(), "INT", 1000, 2)
                    }
                }
                
                val results = deferreds.map { it.await() }
                return@coroutineScope results.any { it }
            } catch (e: Exception) {
                Log.e("VehicleManager", "Failed to write VHAL fan speed", e)
                return@coroutineScope false
            }
        }
    }
    
    fun setGenericVhalProperty(propertyId: Int, areaId: Int, value: String, dataType: String): Boolean {
        try {
            var targetAreaId = areaId
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            
            // If areaId is 0 (global/unassigned), try to fetch the first valid areaId from the config
            if (targetAreaId == 0 && config != null && config.areaIds.isNotEmpty()) {
                targetAreaId = config.areaIds.first()
            }

            when (dataType.uppercase()) {
                "INT" -> {
                    var parsedInt = value.toFloatOrNull()?.toInt() ?: value.toInt()
                    if (config != null) {
                        val min = config.getMinValue(targetAreaId) as? Int
                        val max = config.getMaxValue(targetAreaId) as? Int
                        if (min != null && max != null) parsedInt = parsedInt.coerceIn(min, max)
                    }
                    carPropertyManager?.setIntProperty(propertyId, targetAreaId, parsedInt)
                }
                "FLOAT" -> {
                    var parsedFloat = value.toFloat()
                    if (config != null) {
                        val min = config.getMinValue(targetAreaId) as? Float
                        val max = config.getMaxValue(targetAreaId) as? Float
                        if (min != null && max != null) parsedFloat = parsedFloat.coerceIn(min, max)
                    }
                    carPropertyManager?.setFloatProperty(propertyId, targetAreaId, parsedFloat)
                }
                "BOOLEAN" -> carPropertyManager?.setBooleanProperty(propertyId, targetAreaId, value.toBoolean())
                "STRING" -> carPropertyManager?.setProperty(Any::class.java, propertyId, targetAreaId, value)
                else -> return false
            }
            return true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed generic write for property $propertyId", e)
            return false
        }
    }
    
    suspend fun writeDefrosterToVhalVerified(on: Boolean): Boolean {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_DEFROSTER)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val success = setPropertyVerified(VehiclePropertyIds.HVAC_DEFROSTER, areaId, on.toString(), "BOOLEAN")
            if (!success) return false
            return true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL defroster", e)
            return false
        }
    }

    suspend fun writeRearDefrosterToVhalVerified(on: Boolean): Boolean {
        try {
            // Android Automotive uses HVAC_ELECTRIC_DEFROSTER_ON (0x13200514) for the rear window defroster
            val HVAC_ELECTRIC_DEFROSTER_ON = 320865556
            val config = carPropertyManager?.getCarPropertyConfig(HVAC_ELECTRIC_DEFROSTER_ON)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val success = setPropertyVerified(HVAC_ELECTRIC_DEFROSTER_ON, areaId, on.toString(), "BOOLEAN")
            if (!success) return false
            return true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL rear defroster", e)
            return false
        }
    }

    suspend fun writeSeatHeaterToVhalVerified(level: Int): Boolean {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE)
            config?.areaIds?.forEach { areaId ->
                var finalLevel = level
                var maxLvl = 3
                var minLvl = -3
                try {
                    maxLvl = config.getMaxValue(areaId) as? Int ?: 3
                    minLvl = config.getMinValue(areaId) as? Int ?: -3
                } catch (e: Exception) { }
                if (finalLevel > maxLvl) finalLevel = maxLvl
                if (finalLevel < minLvl) finalLevel = minLvl
                val success = setPropertyVerified(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, areaId, finalLevel.toString(), "INT")
                if (!success) return false
            }
            return true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL seat heater", e)
            return false
        }
    }

    suspend fun writeSeatMassagerToVhalVerified(level: Int): Boolean {
        try {
            // Seat Massage is a hidden API (added in API 33) 
            Log.i("VehicleManager", "Setting Seat Massager to level $level")
            val config = carPropertyManager?.getCarPropertyConfig(356519253) // 0x15400D55 (SEAT_MASSAGE)
            config?.areaIds?.forEach { areaId ->
                var finalLevel = level
                var maxLvl = 3
                var minLvl = 0
                try {
                    maxLvl = config.getMaxValue(areaId) as? Int ?: 3
                    minLvl = config.getMinValue(areaId) as? Int ?: 0
                } catch (e: Exception) { }
                if (finalLevel > maxLvl) finalLevel = maxLvl
                if (finalLevel < minLvl) finalLevel = minLvl
                val success = setPropertyVerified(356519253, areaId, finalLevel.toString(), "INT")
                if (!success) return false
            }
            return true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL seat massager. Possibly unsupported by current HAL.", e)
            return false
        }
    }

    suspend fun writeWindowPositionToVhalVerified(percentage: Int): Boolean {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.WINDOW_POS)
            config?.areaIds?.forEach { areaId ->
                // Assuming percentage is 0-100
                val success = setPropertyVerified(VehiclePropertyIds.WINDOW_POS, areaId, percentage.toString(), "INT")
                if (!success) return false
            }
            return true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write window position", e)
            return false
        }
    }

    fun runPropertyDiagnostics(): String {
        val sb = StringBuilder()
        sb.append("## Telemetry Property Diagnostics\n\n")
        sb.append("| Property Name | Type | Status | Value / Error |\n")
        sb.append("|---|---|---|---|\n")

        for ((id, name) in customPropertyIdToName) {
            val type = customPropertyIdToType[id] ?: "UNKNOWN"
            var status = "✅ PASS"
            var note = "N/A"
            try {
                val config = carPropertyManager?.getCarPropertyConfig(id)
                if (config == null) {
                    status = "⚠️ UNSUPPORTED"
                    note = "Hardware lacks VHAL support for this property"
                } else {
                    val areaId = config.areaIds.firstOrNull() ?: 0
                    val value = when (type.uppercase()) {
                        "FLOAT" -> carPropertyManager?.getFloatProperty(id, areaId).toString()
                        "INT" -> carPropertyManager?.getIntProperty(id, areaId).toString()
                        "BOOLEAN" -> carPropertyManager?.getBooleanProperty(id, areaId).toString()
                        "STRING" -> carPropertyManager?.getProperty<Any>(Any::class.java, id, areaId)?.value?.toString() ?: "null"
                        else -> "Unknown Type"
                    }
                    note = "Read successful: $value"
                }
            } catch (e: Exception) {
                status = "❌ CRASH"
                note = e.message ?: "Exception"
            }
            sb.append("| $name | $type | $status | $note |\n")
        }
        return sb.toString()
    }


    suspend fun setPropertyVerified(propertyId: Int, targetAreaId: Int, value: String, dataType: String, timeoutMs: Long = 1500, maxRetries: Int = 3): Boolean {
        // Pre-check if the value is already set to avoid VHAL timeout (VHAL doesn't fire onChange if value didn't change)
        try {
            val currentValue = when (dataType.uppercase()) {
                "INT" -> carPropertyManager?.getIntProperty(propertyId, targetAreaId)?.toString()
                "FLOAT" -> carPropertyManager?.getFloatProperty(propertyId, targetAreaId)?.toString()
                "BOOLEAN" -> carPropertyManager?.getBooleanProperty(propertyId, targetAreaId)?.toString()
                "STRING" -> carPropertyManager?.getProperty<Any>(Any::class.java, propertyId, targetAreaId)?.value?.toString()
                else -> null
            }
            
            val matches = when (dataType.uppercase()) {
                "INT" -> currentValue?.toFloatOrNull()?.toInt() == value.toFloatOrNull()?.toInt()
                "FLOAT" -> currentValue?.toFloatOrNull() == value.toFloatOrNull()
                "BOOLEAN" -> currentValue?.toBoolean() == value.toBoolean()
                else -> currentValue == value
            }
            
            if (matches) {
                Log.d("VehicleManager", "Property $propertyId area $targetAreaId is already $value. Skipping write.")
                return true
            }
        } catch (e: Exception) {
            Log.w("VehicleManager", "Failed pre-check for property $propertyId", e)
        }

        var currentDelay = 500L
        repeat(maxRetries) { attempt ->
            val success = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                    val callback = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(valueRecord: CarPropertyValue<*>) {
                            if (valueRecord.propertyId == propertyId && valueRecord.areaId == targetAreaId) {
                                val newValue = valueRecord.value
                                val matches = when (dataType.uppercase()) {
                                    "INT" -> newValue == value.toFloatOrNull()?.toInt() ?: value.toIntOrNull()
                                    "FLOAT" -> newValue == value.toFloatOrNull()
                                    "BOOLEAN" -> newValue == value.toBoolean()
                                    else -> newValue.toString() == value
                                }
                                if (matches) {
                                    carPropertyManager?.unregisterCallback(this, propertyId)
                                    if (continuation.isActive) continuation.resume(true)
                                }
                            }
                        }
                        override fun onErrorEvent(propId: Int, zone: Int) {
                            if (propId == propertyId && zone == targetAreaId) {
                                carPropertyManager?.unregisterCallback(this, propertyId)
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                    
                    carPropertyManager?.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    
                    try {
                        val writeSuccess = setGenericVhalProperty(propertyId, targetAreaId, value, dataType)
                        if (!writeSuccess) {
                            carPropertyManager?.unregisterCallback(callback, propertyId)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    } catch (e: Exception) {
                        Log.e("VehicleManager", "Hardware Exception in setGenericVhalProperty for $propertyId", e)
                        carPropertyManager?.unregisterCallback(callback, propertyId)
                        if (continuation.isActive) continuation.resume(false)
                    }

                    continuation.invokeOnCancellation {
                        carPropertyManager?.unregisterCallback(callback, propertyId)
                    }
                }
            } ?: false
            
            if (success) return true
            
            // Manual verification fallback: emulator/HAL may not fire onChangeEvent
            val manuallyVerified = try {
                val currentCheck = when (dataType.uppercase()) {
                    "INT" -> carPropertyManager?.getIntProperty(propertyId, targetAreaId)?.toString()
                    "FLOAT" -> carPropertyManager?.getFloatProperty(propertyId, targetAreaId)?.toString()
                    "BOOLEAN" -> carPropertyManager?.getBooleanProperty(propertyId, targetAreaId)?.toString()
                    "STRING" -> carPropertyManager?.getProperty<Any>(Any::class.java, propertyId, targetAreaId)?.value?.toString()
                    else -> null
                }
                
                when (dataType.uppercase()) {
                    "INT" -> currentCheck?.toFloatOrNull()?.toInt() == value.toFloatOrNull()?.toInt()
                    "FLOAT" -> currentCheck?.toFloatOrNull() == value.toFloatOrNull()
                    "BOOLEAN" -> currentCheck?.toBoolean() == value.toBoolean()
                    else -> currentCheck == value
                }
            } catch (e: Exception) { false }

            if (manuallyVerified) {
                Log.d("VehicleManager", "Property $propertyId area $targetAreaId manually verified as $value after timeout.")
                return true
            }
            
            if (attempt < maxRetries - 1) {
                Log.w("VehicleManager", "Hardware command failed for property $propertyId. Retrying in ${currentDelay}ms (Attempt ${attempt + 1}/$maxRetries)...")
                kotlinx.coroutines.delay(currentDelay)
                currentDelay *= 2
            }
        }
        return false
    }

    fun cleanup() {
        try {
            carPropertyManager?.unregisterCallback(carPropertyCallback)
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to cleanup", e)
        }
    }
}

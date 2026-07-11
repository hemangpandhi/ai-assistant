package com.tcs.vehicleassistant

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VehicleManager {
    private var carPropertyManager: CarPropertyManager? = null
    var isInitialized = false
        private set

    private var currentSpeed: Float = 0f
    private var currentSeatHeaterLevel: Int = 0
    private var currentTemperature: Float = 22f // Store raw VHAL value (usually Celsius)
    private var currentFuelLevel: Float = 50f
    private var currentGear: Int = 4
    var isHvacPowerOn: Boolean = false
    var isHvacAutoOn: Boolean = false
    var isHvacAcOn: Boolean = false
    private var currentFanSpeed: Int = 3
    var isDefrosterOn: Boolean = false
    
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
    
    fun getLLMContextString(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val diningPref = prefs.getString("dining_pref", "Pure Vegetarian") ?: "Pure Vegetarian"
        val customProps = getCustomPropertiesString()
        val customPropsStr = if (customProps.isNotEmpty()) ", $customProps" else ""
        
        return "Speed: ${getRealSpeed()}mph, Temp: ${getRealTemperature()}F, Fan: $currentFanSpeed, Heater: ${getRealSeatHeaterLevel()}, AC: ${if (isHvacAcOn) "ON" else "OFF"}, Power: ${if (isHvacPowerOn) "ON" else "OFF"}, Auto: ${if (isHvacAutoOn) "ON" else "OFF"}, Defrost: ${if (isDefrosterOn) "ON" else "OFF"}, City: ${LocationManager.getCurrentCity(context)}$customPropsStr"
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
                VehiclePropertyIds.HVAC_POWER_ON -> isHvacPowerOn = value.value as? Boolean ?: false
                VehiclePropertyIds.HVAC_AUTO_ON -> isHvacAutoOn = value.value as? Boolean ?: false
                VehiclePropertyIds.HVAC_AC_ON -> isHvacAcOn = value.value as? Boolean ?: false
                VehiclePropertyIds.HVAC_FAN_SPEED -> currentFanSpeed = value.value as? Int ?: 3
                VehiclePropertyIds.HVAC_DEFROSTER -> isDefrosterOn = value.value as? Boolean ?: false
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.e("VehicleManager", "Error reading propertyId: $propertyId for zone: $zone")
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().initialize(context)

            val car = Car.createCar(context)
            carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            
            val propertiesToRegister = listOf(
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                VehiclePropertyIds.HVAC_SEAT_TEMPERATURE,
                VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                VehiclePropertyIds.HVAC_POWER_ON,
                VehiclePropertyIds.HVAC_AUTO_ON,
                VehiclePropertyIds.HVAC_AC_ON,
                VehiclePropertyIds.HVAC_FAN_SPEED,
                VehiclePropertyIds.HVAC_DEFROSTER,
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
                val inputStream = context.assets.open("vehicle_skills_registry.json")
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
                    
                    try {
                        carPropertyManager?.registerCallback(carPropertyCallback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                        Log.i("VehicleManager", "Registered custom JSON property: $name ($id)")
                    } catch (e: Exception) {
                        Log.e("VehicleManager", "Failed to register custom property: $name ($id)", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("VehicleManager", "Error parsing vehicle_skills_registry.json", e)
            }
            
            currentSpeed = getFloatPropertyQuietly(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0f)
            currentSeatHeaterLevel = getIntPropertyQuietly(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, 0)
            currentTemperature = getFloatPropertyQuietly(VehiclePropertyIds.HVAC_TEMPERATURE_SET, 22f)
            currentFuelLevel = getFloatPropertyQuietly(VehiclePropertyIds.FUEL_LEVEL, 50f)
            currentGear = getIntPropertyQuietly(VehiclePropertyIds.GEAR_SELECTION, 4)
            isHvacPowerOn = getBooleanPropertyQuietly(VehiclePropertyIds.HVAC_POWER_ON, false)
            isHvacAutoOn = getBooleanPropertyQuietly(VehiclePropertyIds.HVAC_AUTO_ON, false)
            isHvacAcOn = getBooleanPropertyQuietly(VehiclePropertyIds.HVAC_AC_ON, false)
            currentFanSpeed = getIntPropertyQuietly(VehiclePropertyIds.HVAC_FAN_SPEED, 3)
            isDefrosterOn = getBooleanPropertyQuietly(VehiclePropertyIds.HVAC_DEFROSTER, false)

            isInitialized = true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to initialize CarPropertyManager", e)
        }
    }

    private fun getFloatPropertyQuietly(propertyId: Int, default: Float): Float {
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

    private fun getIntPropertyQuietly(propertyId: Int, default: Int): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.getIntProperty(propertyId, areaId) ?: default
        } catch (e: Exception) { default }
    }

    private fun getBooleanPropertyQuietly(propertyId: Int, default: Boolean): Boolean {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.getBooleanProperty(propertyId, areaId) ?: default
        } catch (e: Exception) { default }
    }

    fun getRealSpeed(): Int = currentSpeed.toInt()
    fun getRealSeatHeaterLevel(): Int = currentSeatHeaterLevel
    fun getRealTemperature(zone: String = "driver"): Int {
        var temp = currentTemperature
        try {
            var areaIds = carPropertyManager?.getCarPropertyConfig(android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(49, 68)
            }
            
            var targetAreaId = areaIds.first()
            for (areaId in areaIds) {
                if (zone == "driver" && (areaId and 4) == 4) {
                    targetAreaId = areaId
                    break
                }
                if (zone == "passenger" && (areaId and 1) == 1) {
                    targetAreaId = areaId
                    break
                }
            }
            
            val readTemp = carPropertyManager?.getFloatProperty(android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET, targetAreaId)
            if (readTemp != null && readTemp > 0) {
                temp = readTemp
            }
        } catch (e: Exception) {
            // fallback to cached currentTemperature
        }

        // VHAL usually stores in Celsius (e.g. 16-32)
        if (temp >= 50f) {
            return Math.round(temp)
        }
        
        return Math.round((temp * 9.0f / 5.0f) + 32.0f)
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

    suspend fun writeTemperatureToVhalVerified(temp: Float, zone: String = "all"): Boolean {
        try {
            var areaIds = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(49, 68) // Fallback for ROW_1_LEFT and ROW_1_RIGHT in AOSP
            }
            
            // Filter areaIds based on the requested zone
            val targetAreaIds = mutableListOf<Int>()
            for (areaId in areaIds) {
                if (zone == "driver" && (areaId and 4) != 4) continue
                if (zone == "passenger" && (areaId and 1) != 1) continue
                targetAreaIds.add(areaId)
            }
            
            if (targetAreaIds.isEmpty()) {
                Log.w("VehicleManager", "No matching area IDs found for zone: $zone")
                return false
            }

            Log.d("VehicleManager", "writeTemperatureToVhal called with $temp, zone: $zone. Target Area IDs: ${targetAreaIds.joinToString()}")
            var anySuccess = false
            
            targetAreaIds.forEach { areaId ->
                var finalTemp = temp
                
                try {
                    val configArray = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.configArray
                    var isVhalFahrenheit = false
                    
                    if (configArray != null && configArray.size >= 2) {
                        val minTemp = configArray[0] / 10f
                        val maxTemp = configArray[1] / 10f
                        val increment = if (configArray.size >= 3) configArray[2] / 10f else 0.5f
                        
                        // If max temp is > 50, the VHAL natively expects Fahrenheit
                        if (maxTemp > 50f || currentTemperature > 50f) {
                            isVhalFahrenheit = true
                        }
                        
                        // If requested temp is Fahrenheit but VHAL expects Celsius, convert to Celsius
                        if (!isVhalFahrenheit && finalTemp > 30f) {
                            // Convert requested Fahrenheit to Celsius using standard math
                            finalTemp = (finalTemp - 32.0f) * 5.0f / 9.0f
                        }
                        
                        if (isVhalFahrenheit) {
                            finalTemp = Math.round(finalTemp).toFloat()
                        } else if (increment > 0) {
                            finalTemp = Math.round(finalTemp / increment) * increment
                        }
                        
                        // Clamp
                        if (finalTemp > maxTemp) finalTemp = maxTemp
                        if (finalTemp < minTemp) finalTemp = minTemp
                    } else {
                        // Fallback
                        if (currentTemperature > 50f) {
                            // Assume VHAL is natively Fahrenheit
                            finalTemp = Math.round(finalTemp).toFloat()
                            if (finalTemp > 85.0f) finalTemp = 85.0f
                            if (finalTemp < 60.0f) finalTemp = 60.0f
                        } else {
                            // Assume VHAL is natively Celsius
                            if (finalTemp > 30f) {
                                finalTemp = (finalTemp - 32.0f) * 5.0f / 9.0f
                            }
                            finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
                            if (finalTemp > 28.0f) finalTemp = 28.0f
                            if (finalTemp < 16.0f) finalTemp = 16.0f
                        }
                    }
                } catch (e: Exception) {
                    // Safety fallback
                    if (finalTemp > 30f) {
                        finalTemp = (finalTemp - 32f) * 5f / 9f
                    }
                    finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
                    if (finalTemp > 28.0f) finalTemp = 28.0f
                    if (finalTemp < 16.0f) finalTemp = 16.0f
                }

                try {
                    Log.d("VehicleManager", "Setting temp for area $areaId to $finalTemp")
                    val success = setPropertyVerified(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId, finalTemp.toString(), "FLOAT")
                    if (success) {
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    Log.e("VehicleManager", "Failed to set temp for area $areaId (tried $finalTemp)", e)
                }
            }
            return anySuccess
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL temp", e)
            return false
        }
    }
    
    suspend fun writeFanSpeedToVhalVerified(speedLevel: Int): Boolean {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(android.car.VehiclePropertyIds.HVAC_FAN_SPEED)
            var areaIds = config?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(117) // Fallback for HVAC_FAN_SPEED
            }
            
            ensureHvacPowerOn()
            
            var anySuccess = false
            areaIds.forEach { areaId ->
                var finalLevel = speedLevel
                var maxLvl = 7
                var minLvl = 1
                try {
                    maxLvl = config?.getMaxValue(areaId) as? Int ?: 7
                    minLvl = config?.getMinValue(areaId) as? Int ?: 1
                } catch (e: Exception) {}
                
                if (finalLevel > maxLvl) finalLevel = maxLvl
                if (finalLevel < minLvl) finalLevel = minLvl
                
                val success = setPropertyVerified(android.car.VehiclePropertyIds.HVAC_FAN_SPEED, areaId, finalLevel.toString(), "INT")
                if (success) anySuccess = true
            }
            return anySuccess
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL fan speed", e)
            return false
        }
    }
    
    suspend fun writeAirflowDirectionToVhalVerified(direction: Int): Boolean {
        try {
            val propertyId = 356517121 // HVAC_FAN_DIRECTION
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            var areaIds = config?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(49, 68) // Fallback for ROW_1_LEFT/RIGHT
            }
            
            ensureHvacPowerOn()
            
            var anySuccess = false
            areaIds.forEach { areaId ->
                val success = setPropertyVerified(propertyId, areaId, direction.toString(), "INT")
                if (success) anySuccess = true
            }
            return anySuccess
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL airflow direction", e)
            return false
        }
    }
    fun setGenericVhalProperty(propertyId: Int, areaId: Int, value: String, dataType: String): Boolean {
        try {
            var targetAreaId = areaId
            // If areaId is 0 (global/unassigned), try to fetch the first valid areaId from the config
            if (targetAreaId == 0) {
                val config = carPropertyManager?.getCarPropertyConfig(propertyId)
                if (config != null && config.areaIds.isNotEmpty()) {
                    targetAreaId = config.areaIds.first()
                }
            }

            when (dataType.uppercase()) {
                "INT" -> carPropertyManager?.setIntProperty(propertyId, targetAreaId, value.toFloatOrNull()?.toInt() ?: value.toInt())
                "FLOAT" -> carPropertyManager?.setFloatProperty(propertyId, targetAreaId, value.toFloat())
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
            var areaIds = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_DEFROSTER)?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(1, 2) // Fallback for WINDOW_FRONT and WINDOW_REAR
            }
            
            var anySuccess = false
            areaIds.forEach { areaId ->
                val success = setPropertyVerified(VehiclePropertyIds.HVAC_DEFROSTER, areaId, on.toString(), "BOOLEAN")
                if (success) anySuccess = true
            }
            return anySuccess
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL defroster", e)
            return false
        }
    }

    suspend fun writeRearDefrosterToVhalVerified(on: Boolean): Boolean {
        try {
            // Android Automotive uses HVAC_ELECTRIC_DEFROSTER_ON (0x13200514) for the rear window defroster
            val HVAC_ELECTRIC_DEFROSTER_ON = 320865556
            var areaIds = carPropertyManager?.getCarPropertyConfig(HVAC_ELECTRIC_DEFROSTER_ON)?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(2) // Fallback for WINDOW_REAR
            }
            var anySuccess = false
            areaIds.forEach { areaId ->
                val success = setPropertyVerified(HVAC_ELECTRIC_DEFROSTER_ON, areaId, on.toString(), "BOOLEAN")
                if (success) anySuccess = true
            }
            return anySuccess
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL rear defroster", e)
            return false
        }
    }

    suspend fun writeSeatHeaterToVhalVerified(level: Int): Boolean {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE)
            var areaIds = config?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(1, 4) // Fallback for SEAT_1_LEFT and SEAT_1_RIGHT
            }
            
            ensureHvacPowerOn()
            
            var anySuccess = false
            areaIds.forEach { areaId ->
                var finalLevel = level
                var maxLvl = 3
                var minLvl = -3
                try {
                    maxLvl = config?.getMaxValue(areaId) as? Int ?: 3
                    minLvl = config?.getMinValue(areaId) as? Int ?: -3
                } catch (e: Exception) { }
                if (finalLevel > maxLvl) finalLevel = maxLvl
                if (finalLevel < minLvl) finalLevel = minLvl
                val success = setPropertyVerified(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, areaId, finalLevel.toString(), "INT")
                if (success) anySuccess = true
            }
            return anySuccess
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
            
            ensureHvacPowerOn()
            
            config?.areaIds?.forEach { areaId ->
                var finalLevel = level
                var maxLvl = 3
                var minLvl = 0
                try {
                    maxLvl = config?.getMaxValue(areaId) as? Int ?: 3
                    minLvl = config?.getMinValue(areaId) as? Int ?: 0
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

    private suspend fun ensureHvacPowerOn() {
        try {
            // First try area 117 (0x75), then area 1
            if (!setPropertyVerified(android.car.VehiclePropertyIds.HVAC_POWER_ON, 117, "true", "BOOLEAN", 100)) {
                setPropertyVerified(android.car.VehiclePropertyIds.HVAC_POWER_ON, 1, "true", "BOOLEAN", 100)
            }
        } catch (e: Exception) {
            Log.w("VehicleManager", "Failed to ensure HVAC power on: ${e.message}")
        }
    }

    suspend fun setPropertyVerified(propertyId: Int, targetAreaIdParam: Int, value: String, dataType: String, timeoutMs: Long = 500, maxRetries: Int = 2): Boolean {
        var targetAreaId = targetAreaIdParam
        if (targetAreaId == 0) {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            if (config != null && config.areaIds.isNotEmpty()) {
                targetAreaId = config.areaIds.first()
            }
        }
        
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
                try {
                    val writeSuccess = setGenericVhalProperty(propertyId, targetAreaId, value, dataType)
                    if (!writeSuccess) return@withTimeoutOrNull false

                    while (true) {
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

                        if (manuallyVerified) return@withTimeoutOrNull true
                        kotlinx.coroutines.delay(50)
                    }
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("VehicleManager", "Hardware Exception in setGenericVhalProperty for $propertyId", e)
                    false
                }
            } ?: false
            
            if (success) return true
            
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

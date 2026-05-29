package com.example.gemininano

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

object VehicleManager {
    private var carPropertyManager: CarPropertyManager? = null
    var isInitialized = false
        private set

    private var currentSpeed: Float = 0f
    private var currentSeatHeaterLevel: Int = 0
    private var currentTemperature: Float = 22f // Store raw VHAL value (usually Celsius)
    private var currentFuelLevel: Float = 50f
    private var currentGear: Int = 4
    
    // Expanded Mock Telemetry
    private var currentEvBatteryLevel: Float = 42f
    private var currentTirePressureFrontLeft: Float = 28f // Low pressure
    private var currentOutsideTemperature: Float = 32f // Freezing outside
    private var currentObdCodes: String = "P0420"
    
    fun getObdCodes(): String {
        return currentObdCodes
    }

    private val carPropertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> currentSpeed = value.value as? Float ?: 0f
                VehiclePropertyIds.HVAC_SEAT_TEMPERATURE -> currentSeatHeaterLevel = value.value as? Int ?: 0
                VehiclePropertyIds.HVAC_TEMPERATURE_SET -> currentTemperature = value.value as? Float ?: 22f
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
            val car = Car.createCar(context)
            carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            
            carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.HVAC_TEMPERATURE_SET, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.FUEL_LEVEL, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            try {
                carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.WINDOW_POS, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            } catch (e: Exception) {
                Log.w("VehicleManager", "Could not register window pos callback")
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

    private fun getFloatPropertyQuietly(propertyId: Int, default: Float): Float {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(propertyId)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.getFloatProperty(propertyId, areaId) ?: default
        } catch (e: Exception) { default }
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
        // If it's less than 50, assume Celsius and convert to Fahrenheit for the LLM
        return if (currentTemperature < 50f) {
            ((currentTemperature * 9f / 5f) + 32f).toInt()
        } else {
            currentTemperature.toInt()
        }
    }
    fun getRawTemperature(): Float = currentTemperature
    fun getFuelLevel(): Float = currentFuelLevel
    fun getGearSelection(): String {
        return when (currentGear) {
            1 -> "Neutral"
            2 -> "Reverse"
            4 -> "Park"
            8 -> "Drive"
            else -> "Unknown"
        }
    }

    // Expanded Telemetry Accessors
    fun getEvBatteryLevel(): Float = currentEvBatteryLevel
    fun getTirePressureFrontLeft(): Float = currentTirePressureFrontLeft
    fun getOutsideTemperature(): Float = currentOutsideTemperature

    // Mock Telemetry Setters (for testing)
    fun setMockSpeed(speed: Float) { currentSpeed = speed }
    fun setMockEvBatteryLevel(level: Float) { currentEvBatteryLevel = level }
    fun setMockTirePressure(pressure: Float) { currentTirePressureFrontLeft = pressure }
    fun setMockOutsideTemperature(temp: Float) { currentOutsideTemperature = temp }

    fun writeTemperatureToVhal(temp: Float) {
        try {
            var areaIds = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.areaIds
            if (areaIds == null || areaIds.isEmpty()) {
                areaIds = intArrayOf(17, 49) // Fallback for SEAT_1_LEFT and SEAT_1_RIGHT in AOSP
            }
            
            areaIds.forEach { areaId ->
                var finalTemp = temp
                // If requested temp is clearly in Fahrenheit (e.g., > 30), convert to Celsius
                // Car HVAC max Celsius is usually 28.0C, so anything above 30 is Fahrenheit
                if (finalTemp > 30f) {
                    finalTemp = (finalTemp - 32f) * 5f / 9f
                }
                
                // Android Automotive HVAC UI strictly requires temperatures to be rounded to the nearest 0.5
                finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
                
                // Clamp to safe limits (16.0C to 28.0C) to prevent VHAL IllegalArgumentException
                try {
                    val configArray = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)?.configArray
                    if (configArray != null && configArray.size >= 2) {
                        val minC = configArray[0] / 10f
                        val maxC = configArray[1] / 10f
                        if (finalTemp > maxC) finalTemp = maxC
                        if (finalTemp < minC) finalTemp = minC
                    } else {
                        if (finalTemp > 28.0f) finalTemp = 28.0f
                        if (finalTemp < 16.0f) finalTemp = 16.0f
                    }
                } catch (e: Exception) {
                    if (finalTemp > 28.0f) finalTemp = 28.0f
                    if (finalTemp < 16.0f) finalTemp = 16.0f
                }

                try {
                    Log.d("VehicleManager", "Setting temp for area $areaId to $finalTemp")
                    carPropertyManager?.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId, finalTemp)
                } catch (e: Exception) {
                    Log.e("VehicleManager", "Failed to set temp for area $areaId (tried $finalTemp)", e)
                }
            }
            currentTemperature = temp
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL temp", e)
        }
    }
    
    fun writeDefrosterToVhal(on: Boolean) {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_DEFROSTER)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.setBooleanProperty(VehiclePropertyIds.HVAC_DEFROSTER, areaId, on)
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL defroster", e)
        }
    }

    fun writeSeatHeaterToVhal(level: Int) {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE)
            config?.areaIds?.forEach { areaId ->
                var finalLevel = level
                if (finalLevel > 3) finalLevel = 3
                if (finalLevel < 0) finalLevel = 0
                carPropertyManager?.setIntProperty(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, areaId, finalLevel)
            }
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL seat heater", e)
        }
    }

    fun writeSeatMassagerToVhal(level: Int) {
        try {
            // Seat Massage is a hidden API (added in API 33) 
            Log.i("VehicleManager", "Setting Seat Massager to level $level")
            val config = carPropertyManager?.getCarPropertyConfig(356519253) // 0x15400D55 (SEAT_MASSAGE)
            config?.areaIds?.forEach { areaId ->
                var finalLevel = level
                if (finalLevel > 3) finalLevel = 3
                if (finalLevel < 0) finalLevel = 0
                carPropertyManager?.setIntProperty(356519253, areaId, finalLevel)
            }
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL seat massager. Possibly unsupported by current HAL.", e)
        }
    }

    fun writeWindowPositionToVhal(percentage: Int) {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.WINDOW_POS)
            config?.areaIds?.forEach { areaId ->
                // Assuming percentage is 0-100
                carPropertyManager?.setIntProperty(VehiclePropertyIds.WINDOW_POS, areaId, percentage)
            }
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL window pos", e)
        }
    }

    fun cleanup() {
        try {
            carPropertyManager?.unregisterCallback(carPropertyCallback)
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to cleanup", e)
        }
    }
}

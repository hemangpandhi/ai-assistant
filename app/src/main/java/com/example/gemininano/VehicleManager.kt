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

    fun writeTemperatureToVhal(temp: Float) {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
            config?.areaIds?.forEach { areaId ->
                val maxTemp = config.getMaxValue(areaId) as? Float ?: 32f
                val minTemp = config.getMinValue(areaId) as? Float ?: 16f
                
                var finalTemp = temp
                // If requested temp is clearly in Fahrenheit (e.g., > 50), convert to Celsius
                if (finalTemp > 50f && maxTemp < 50f) {
                    finalTemp = (finalTemp - 32f) * 5f / 9f
                }
                
                // Clamp to VHAL limits to avoid IllegalArgumentException
                if (finalTemp > maxTemp) finalTemp = maxTemp
                if (finalTemp < minTemp) finalTemp = minTemp
                
                // Android Automotive HVAC UI strictly requires temperatures to be rounded
                // to the nearest 0.5 (or according to configArray). If we pass 23.88889,
                // the SystemUI will completely ignore the update.
                finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
                
                carPropertyManager?.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId, finalTemp)
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

    fun cleanup() {
        try {
            carPropertyManager?.unregisterCallback(carPropertyCallback)
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to cleanup", e)
        }
    }
}

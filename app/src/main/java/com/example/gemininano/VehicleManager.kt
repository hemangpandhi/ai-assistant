package com.example.gemininano

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

object VehicleManager {
    private var carPropertyManager: CarPropertyManager? = null
    var isInitialized = false
        private set

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val car = Car.createCar(context)
            carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            isInitialized = true
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to initialize CarPropertyManager", e)
        }
    }

    fun getRealSpeed(): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val speed = carPropertyManager?.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, areaId) ?: 0f
            // VHAL speed is usually m/s, let's just return it as Int (or if it's already mph/kmh based on config, returning Int is fine)
            speed.toInt()
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to read VHAL speed", e)
            0
        }
    }

    fun getRealSeatHeaterLevel(): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val level = carPropertyManager?.getIntProperty(VehiclePropertyIds.HVAC_SEAT_TEMPERATURE, areaId) ?: 0
            level
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to read VHAL seat heater", e)
            0
        }
    }

    fun getRealTemperature(): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val currentTemp = carPropertyManager?.getFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId) ?: 72f
            currentTemp.toInt()
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to read VHAL temp", e)
            72
        }
    }

    fun writeTemperatureToVhal(temp: Float) {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId, temp)
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to write VHAL temp", e)
        }
    }
}

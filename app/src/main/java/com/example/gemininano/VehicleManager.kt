package com.example.gemininano

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

data class MockVehicleState(
    var speed: Int = 65,
    var temperature: Int = 72,
    var seatHeaterLevel: Int = 0,
    var fuelLevelPercent: Int = 45,
    var isMediaPlaying: Boolean = false,
    var ambientLighting: String = "White",
    var windowsOpen: Boolean = false
)

object VehicleManager {
    val vehicleState = MockVehicleState()
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

    fun getRealTemperature(): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val currentTemp = carPropertyManager?.getFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId) ?: vehicleState.temperature.toFloat()
            currentTemp.toInt()
        } catch (e: Exception) {
            Log.e("VehicleManager", "Failed to read VHAL temp", e)
            vehicleState.temperature
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

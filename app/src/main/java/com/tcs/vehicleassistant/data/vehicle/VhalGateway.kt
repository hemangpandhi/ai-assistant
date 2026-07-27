package com.tcs.vehicleassistant.data.vehicle

import android.content.Context

/**
 * Port over AAOS VHAL reads/writes. Handlers and telemetry should depend on this,
 * not the [com.tcs.vehicleassistant.VehicleManager] object directly.
 */
interface VhalGateway {
    fun getFloatProperty(propertyId: Int): Float?
    fun setGenericVhalProperty(propertyId: Int, areaId: Int, value: String, dataType: String): Boolean
    fun getLLMContextString(context: Context): String
    fun getRealTemperature(zone: String = "driver"): Int
    fun getRealFanSpeed(): Int
    suspend fun writeTemperatureToVhalVerified(temp: Float, zone: String = "all"): Boolean
    suspend fun writeFanSpeedToVhalVerified(speedLevel: Int): Boolean
    suspend fun writeAirflowDirectionToVhalVerified(direction: Int): Boolean
    suspend fun writeDefrosterToVhalVerified(on: Boolean): Boolean
    suspend fun writeRearDefrosterToVhalVerified(on: Boolean): Boolean
    suspend fun writeSeatHeaterToVhalVerified(level: Int): Boolean
    suspend fun writeSeatMassagerToVhalVerified(level: Int): Boolean
    suspend fun writeWindowPositionToVhalVerified(percentage: Int): Boolean
    suspend fun setPropertyVerified(
        propertyId: Int,
        targetAreaIdParam: Int,
        value: String,
        dataType: String,
        timeoutMs: Long = 500,
        maxRetries: Int = 2,
    ): Boolean
    fun runPropertyDiagnostics(): String
}

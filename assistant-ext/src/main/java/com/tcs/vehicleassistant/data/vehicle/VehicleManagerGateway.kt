package com.tcs.vehicleassistant.data.vehicle

import android.content.Context
import com.tcs.vehicleassistant.VehicleManager

/** Default [VhalGateway] delegating to the legacy VehicleManager singleton. */
class VehicleManagerGateway : VhalGateway {
    override fun getFloatProperty(propertyId: Int): Float? =
        VehicleManager.getFloatProperty(propertyId)

    override fun setGenericVhalProperty(
        propertyId: Int,
        areaId: Int,
        value: String,
        dataType: String,
    ): Boolean = VehicleManager.setGenericVhalProperty(propertyId, areaId, value, dataType)

    override fun getLLMContextString(context: Context): String =
        VehicleManager.getLLMContextString(context)

    override fun getRealTemperature(zone: String): Int =
        VehicleManager.getRealTemperature(zone)

    override fun getRealFanSpeed(): Int = VehicleManager.getRealFanSpeed()

    override suspend fun writeTemperatureToVhalVerified(temp: Float, zone: String): Boolean =
        VehicleManager.writeTemperatureToVhalVerified(temp, zone)

    override suspend fun writeFanSpeedToVhalVerified(speedLevel: Int): Boolean =
        VehicleManager.writeFanSpeedToVhalVerified(speedLevel)

    override suspend fun writeAirflowDirectionToVhalVerified(direction: Int): Boolean =
        VehicleManager.writeAirflowDirectionToVhalVerified(direction)

    override suspend fun writeDefrosterToVhalVerified(on: Boolean): Boolean =
        VehicleManager.writeDefrosterToVhalVerified(on)

    override suspend fun writeRearDefrosterToVhalVerified(on: Boolean): Boolean =
        VehicleManager.writeRearDefrosterToVhalVerified(on)

    override suspend fun writeSeatHeaterToVhalVerified(level: Int): Boolean =
        VehicleManager.writeSeatHeaterToVhalVerified(level)

    override suspend fun writeSeatMassagerToVhalVerified(level: Int): Boolean =
        VehicleManager.writeSeatMassagerToVhalVerified(level)

    override suspend fun writeWindowPositionToVhalVerified(percentage: Int): Boolean =
        VehicleManager.writeWindowPositionToVhalVerified(percentage)

    override suspend fun setPropertyVerified(
        propertyId: Int,
        targetAreaIdParam: Int,
        value: String,
        dataType: String,
        timeoutMs: Long,
        maxRetries: Int,
    ): Boolean = VehicleManager.setPropertyVerified(
        propertyId, targetAreaIdParam, value, dataType, timeoutMs, maxRetries,
    )

    override fun runPropertyDiagnostics(): String = VehicleManager.runPropertyDiagnostics()
}

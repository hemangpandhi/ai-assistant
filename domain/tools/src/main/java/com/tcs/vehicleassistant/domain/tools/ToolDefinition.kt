package com.tcs.vehicleassistant.domain.tools

data class Constraint(
    val propertyId: Int,
    val operator: String,
    val value: Double,
    val errorMsg: String
)

data class ToolDefinition(
    val handlerType: String,
    val promptString: String,
    val handlerKey: String?,
    val propertyId: Int?,
    val dataType: String?,
    val areaId: Int?,
    val valueToWrite: String?,
    val successMessage: String?,
    val errorMessage: String?,
    val description: String?,
    val instruction: String?,
    val keywords: List<String>?,
    val aliases: List<String>?,
    val constraints: List<Constraint>?,
    val requiresConfirmation: Boolean = false,
    val confirmationMessage: String? = null,
    val offlineCapable: Boolean = false,
    val requiresVehicleState: Boolean = false,
    val requiresAgenticLoop: Boolean = false,
    val directExecutable: Boolean = false,
)

data class SystemInstruction(
    val instruction: String,
    val keywords: List<String>,
)

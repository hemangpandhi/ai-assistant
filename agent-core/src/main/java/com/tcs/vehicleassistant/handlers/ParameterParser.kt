package com.tcs.vehicleassistant.handlers

object ParameterParser {
    fun extractDouble(args: String, default: Double = 0.0): Double {
        return Math.abs(Regex("-?\\d+(\\.\\d+)?").find(args)?.value?.toDoubleOrNull() ?: default)
    }

    fun extractInt(args: String, default: Int = 0): Int {
        return Math.abs(Regex("-?\\d+").find(args)?.value?.toIntOrNull() ?: default)
    }

    fun extractString(args: String, default: String = ""): String {
        return if (args.isBlank()) default else args.trim().replace("\"", "")
    }
}

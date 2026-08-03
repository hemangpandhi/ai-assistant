package com.tcs.vehicleassistant.core

object VisionState {
    // Defines where the driver is currently looking
    // Valid values: "Looking Forward", "Driver Window", "Passenger Window", "Center Console", "Rearview Mirror"
    var driverGaze: String = "Looking Forward"

    // Defines the current detected emotional state of the driver
    // Valid values: "Neutral", "Happy", "Stressed", "Drowsy"
    var driverEmotion: String = "Neutral"

    var recognizedUser: String? = null

    /**
     * Formats the vision context for LLM injection.
     */
    fun getVisionContextString(): String {
        return "Driver Gaze: $driverGaze, Driver Emotion: $driverEmotion" + (recognizedUser?.let { ", Recognized User: $it" } ?: "")
    }
}
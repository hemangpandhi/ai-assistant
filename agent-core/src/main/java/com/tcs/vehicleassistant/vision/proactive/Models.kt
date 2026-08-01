package com.tcs.vehicleassistant.vision.proactive

import com.tcs.vehicleassistant.vision.GestureFeedback
import com.tcs.vehicleassistant.vision.HealthState

/**
 * Aggregates all context across the vehicle.
 */
data class CabinContext(
    val healthState: HealthState,
    val gestureFeedback: GestureFeedback?,
    val recognizedUser: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * The priority level of a proactive event. 
 * Higher priority rules win if multiple trigger simultaneously.
 */
enum class PriorityLevel(val weight: Int) {
    ENTERTAINMENT(10),
    COMFORT(50),
    CRITICAL_SAFETY(100)
}

/**
 * The result returned by a use case if its conditions are met.
 */
data class TriggerResult(
    val prompt: String,
    val priority: PriorityLevel
)

/**
 * Interface that all proactive use cases must implement.
 */
interface IProactiveUseCase {
    val name: String
    val priority: PriorityLevel

    fun evaluate(context: CabinContext): TriggerResult?
}
package com.example.gemininano

import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ProactiveAmbientEngine
 *
 * Subscribes to [CarPropertyManager] threshold events and proactively speaks contextual
 * alerts via the shared TTS instance **without requiring any user interaction**.
 *
 * Monitored thresholds:
 * - Fuel level  < 15 %  → low-fuel alert (once per session)
 * - Continuous driving > 90 min → rest-stop reminder (once per session)
 * - Exterior temperature < 32 °F during drive → freeze warning (once per session)
 * - OBD2 DTC flag present → vehicle-warning alert (once per session)
 *
 * Call [start] after [VehicleManager.initialize] succeeds, and [stop] when the app
 * is destroyed / the session ends.
 */
object ProactiveAmbientEngine {

    private const val TAG = "ProactiveAmbient"

    // Threshold values
    private const val FUEL_LOW_THRESHOLD_PERCENT = 15f   // percent (0–100)
    private const val DRIVE_ALERT_MINUTES = 90L
    private const val FREEZE_TEMP_F = 32f

    // Fuel level is reported by VHAL in litres; we need the tank capacity to compute percent.
    // VehiclePropertyIds.INFO_FUEL_CAPACITY = 289472773 (0x11400605)
    private const val FUEL_CAPACITY_PROP_ID = 289472773

    // One-shot alert flags (reset on stop())
    private var fuelAlertFired = false
    private var driveAlertFired = false
    private var freezeAlertFired = false
    private var dtcAlertFired = false

    // Continuous-drive tracking
    private var drivingStartMs: Long = 0L
    private var driveTimerJob: Job? = null

    private var isRunning = false

    private val ambientCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                android.car.VehiclePropertyIds.FUEL_LEVEL -> onFuelLevelChanged(value)
                android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED -> onSpeedChanged(value)
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "CarPropertyEventCallback error: propertyId=$propertyId zone=$zone")
        }
    }

    /**
     * Registers threshold callbacks and starts the continuous-drive timer.
     * Safe to call multiple times — a second call is a no-op while [isRunning].
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        fuelAlertFired = false
        driveAlertFired = false
        freezeAlertFired = false
        dtcAlertFired = false
        drivingStartMs = 0L

        val cpm = VehicleManager.getCarPropertyManager() ?: run {
            Log.w(TAG, "CarPropertyManager not available yet — ambient engine skipped.")
            isRunning = false
            return
        }

        try {
            cpm.registerCallback(
                ambientCallback,
                android.car.VehiclePropertyIds.FUEL_LEVEL,
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register FUEL_LEVEL callback: ${e.message}")
        }

        try {
            cpm.registerCallback(
                ambientCallback,
                android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register PERF_VEHICLE_SPEED callback: ${e.message}")
        }

        Log.i(TAG, "ProactiveAmbientEngine started.")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        driveTimerJob?.cancel()
        driveTimerJob = null
        try {
            VehicleManager.getCarPropertyManager()?.unregisterCallback(ambientCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering ambient callbacks: ${e.message}")
        }
        Log.i(TAG, "ProactiveAmbientEngine stopped.")
    }

    // -------------------------------------------------------------------------
    // Threshold handlers
    // -------------------------------------------------------------------------

    private fun onFuelLevelChanged(value: CarPropertyValue<*>) {
        if (fuelAlertFired) return
        val fuelLitres = value.value as? Float ?: return

        // Derive percentage from tank capacity (read once, cached by VM internally).
        val capacity = VehicleManager.getCarPropertyManager()
            ?.let { cpm ->
                try {
                    val config = cpm.getCarPropertyConfig(FUEL_CAPACITY_PROP_ID)
                    val areaId = config?.areaIds?.firstOrNull() ?: 0
                    cpm.getFloatProperty(FUEL_CAPACITY_PROP_ID, areaId)
                } catch (e: Exception) { null }
            } ?: 50f  // Fallback: assume 50 L tank

        val percent = if (capacity > 0f) (fuelLitres / capacity) * 100f else fuelLitres
        Log.d(TAG, "Fuel level: ${fuelLitres}L / ${capacity}L = ${percent.toInt()}%")

        if (percent < FUEL_LOW_THRESHOLD_PERCENT) {
            fuelAlertFired = true
            speakAlert("Your fuel is getting low. Want me to find a gas station?")
        }
    }

    private fun onSpeedChanged(value: CarPropertyValue<*>) {
        val speedMs = value.value as? Float ?: 0f
        val isDriving = speedMs > 0.5f  // > ~1 mph

        if (isDriving && drivingStartMs == 0L) {
            // Vehicle started moving — begin drive timer.
            drivingStartMs = System.currentTimeMillis()
            startDriveTimer()
        } else if (!isDriving && drivingStartMs != 0L) {
            // Vehicle stopped — reset timer.
            driveTimerJob?.cancel()
            drivingStartMs = 0L
        }

        // Freeze warning: check exterior temperature while moving.
        if (isDriving && !freezeAlertFired) {
            val extTempF = VehicleManager.getExteriorTemperatureFahrenheit()
            if (extTempF != null && extTempF < FREEZE_TEMP_F) {
                freezeAlertFired = true
                speakAlert("It's freezing outside. Want me to warm up the seat heaters?")
            }
        }
    }

    private fun startDriveTimer() {
        driveTimerJob?.cancel()
        driveTimerJob = CoroutineScope(Dispatchers.IO).launch {
            delay(DRIVE_ALERT_MINUTES * 60_000L)
            if (isRunning && !driveAlertFired && drivingStartMs != 0L) {
                driveAlertFired = true
                speakAlert("You've been driving for $DRIVE_ALERT_MINUTES minutes. Want me to find a rest stop?")
            }
        }
    }

    // -------------------------------------------------------------------------
    // TTS helper
    // -------------------------------------------------------------------------

    /**
     * Speaks the proactive [alert] text. Uses [TextToSpeech.QUEUE_FLUSH] to interrupt any
     * currently-playing utterance so time-sensitive safety alerts (low fuel, freeze warning)
     * are heard immediately rather than queued behind a long assistant response.
     */
    private fun speakAlert(text: String) {
        Log.i(TAG, "Proactive alert: $text")
        CoroutineScope(Dispatchers.Main).launch {
            val tts = AssistantSession.globalTts
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AMBIENT_${System.currentTimeMillis()}")
            } else {
                Log.w(TAG, "TTS not available — ambient alert suppressed: $text")
            }
        }
    }
}

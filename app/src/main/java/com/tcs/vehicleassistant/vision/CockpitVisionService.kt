package com.tcs.vehicleassistant.vision

import com.tcs.vehicleassistant.repository.UiUxAgentOrchestrator
import com.tcs.vehicleassistant.VehicleManager
import org.koin.android.ext.android.inject

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tcs.vehicleassistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import android.speech.tts.TextToSpeech
import java.util.Locale

class CockpitVisionService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var streamManager: CameraStreamManager? = null
    private lateinit var gestureProcessor: GestureProcessor
    private lateinit var healthProcessor: HealthProcessor
    private lateinit var visionBridge: VisionOrchestratorBridge
    private lateinit var faceIdentityProcessor: FaceIdentityProcessor
    private lateinit var faceProfileManager: FaceProfileManager

    private var latestGestureFeedback: GestureFeedback? = null
    private var identityCheckCounter = 0
    private var lastFrame: Bitmap? = null

    // Face Recognition State
    var recognizedUserName: String = "Guest"
    private var identitySimilarity: Float = 0f

    // Callbacks for UI
    var onFrameCallback: ((Bitmap) -> Unit)? = null
    // Updated signature to include recognized name
    var onStatsUpdateCallback: ((HealthState, GestureFeedback?, Float, String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): CockpitVisionService = this@CockpitVisionService
    }

    private var latestHealthState: HealthState = HealthState(72, "Low", false)

    private lateinit var aaosUserSwitchManager: com.tcs.vehicleassistant.hardware.AAOSUserSwitchManager
    private var currentDriverName: String = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()

        val orchestrator: UiUxAgentOrchestrator by inject()
        visionBridge = VisionOrchestratorBridge(this, orchestrator)
        faceIdentityProcessor = FaceIdentityProcessor(this)
        faceProfileManager = FaceProfileManager(this)
        aaosUserSwitchManager = com.tcs.vehicleassistant.hardware.AAOSUserSwitchManager(this)

        gestureProcessor = GestureProcessor(this) { feedback ->
            latestGestureFeedback = feedback
            com.tcs.vehicleassistant.hardware.CabinCameraManager.currentMood = feedback.mood
            onStatsUpdateCallback?.invoke(latestHealthState, feedback, identitySimilarity, recognizedUserName)
        }

        healthProcessor = HealthProcessor { state ->
            latestHealthState = state
            latestGestureFeedback?.let { feedback ->
                visionBridge.onHealthUpdate(state, feedback)
            }
            onStatsUpdateCallback?.invoke(state, latestGestureFeedback, identitySimilarity, recognizedUserName)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("CAMERA_URL")
        if (!url.isNullOrEmpty()) {
            startStream(url)
        }
        return START_STICKY
    }

    private fun startStream(url: String) {
        val flags = com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags(this)
        if (!flags.proactiveVisionEnabled) {
            android.util.Log.i(
                "CockpitVisionService",
                "Proactive vision disabled; skipping camera start",
            )
            return
        }

        streamManager?.stop()
        com.tcs.vehicleassistant.hardware.CabinCameraManager.frameCallback = null

        if (url == "native") {
            com.tcs.vehicleassistant.hardware.CabinCameraManager.frameCallback = { bitmap ->
                handleIncomingFrame(bitmap)
            }
            com.tcs.vehicleassistant.hardware.CabinCameraManager.startCamera(
                this,
                androidx.lifecycle.ProcessLifecycleOwner.get()
            )
        } else {
            streamManager = CameraStreamManager(
                onFrame = { bitmap ->
                    handleIncomingFrame(bitmap)
                },
                onConnected = { },
                onError = { }
            )
            streamManager?.startStream(url)
        }
    }

    private fun handleIncomingFrame(bitmap: Bitmap) {
        lastFrame = bitmap
        onFrameCallback?.invoke(bitmap)

        scope.launch {
            gestureProcessor.processingFrame(bitmap)
            val faceResult = gestureProcessor.lastFaceResult
            val currentMood = latestGestureFeedback?.mood
            healthProcessor.processFrame(bitmap, faceResult, currentMood)

            identityCheckCounter++
            if (identityCheckCounter % 30 == 0) { // Every ~3 seconds at 10fps
                val embedding = faceIdentityProcessor.extractEmbedding(bitmap)
                embedding?.let { currentEmb ->
                    // Compare against all stored profiles
                    val allProfiles = faceProfileManager.getAllProfiles()
                    var bestMatchName = "Guest"
                    var highestSimilarity = 0f

                    for ((name, savedEmb) in allProfiles) {
                        val sim = faceIdentityProcessor.computeSimilarity(currentEmb, savedEmb)
                        if (sim > highestSimilarity) {
                            highestSimilarity = sim
                            bestMatchName = name
                        }
                    }

                    identitySimilarity = highestSimilarity
                    // 0.6 is a standard threshold for FaceNet cosine similarity
                    if (highestSimilarity > 0.6f) {
                        recognizedUserName = bestMatchName
                        VisionState.recognizedUser = bestMatchName

                        // Trigger zero-touch AAOS user switch & VHAL preferences if driver changed
                        if (recognizedUserName != currentDriverName) {
                            currentDriverName = recognizedUserName
                            val targetUserId = faceProfileManager.getOsUserId(recognizedUserName)
                            val targetTemp = faceProfileManager.getTargetTemp(recognizedUserName)

                            android.util.Log.d("CockpitVisionService", "Face ID verified for $recognizedUserName! Triggering user switch to User ID $targetUserId")
                            aaosUserSwitchManager.switchUser(targetUserId, recognizedUserName)
                            VehicleManager.applySavedCabinPreferences(recognizedUserName, targetTemp)
                        }
                    } else {
                        recognizedUserName = "Guest"
                        VisionState.recognizedUser = "Guest"
                    }
                }
            }
        }
    }

    fun saveCurrentFace(name: String, osUserId: Int = 10, targetTemp: Float = 20.0f) {
        lastFrame?.let { bitmap ->
            val embedding = faceIdentityProcessor.extractEmbedding(bitmap)
            if (embedding != null) {
                faceProfileManager.saveProfile(name, embedding, osUserId, targetTemp)
                android.util.Log.d("CockpitVisionService", "Saved face profile for $name (User ID: $osUserId, Temp: $targetTemp°C)")
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        streamManager?.stop()
        com.tcs.vehicleassistant.hardware.CabinCameraManager.frameCallback = null
    }

    private fun startForegroundServiceNotification() {
        val channelId = "cockpit_vision_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Cockpit Vision Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cockpit AI")
            .setContentText("Monitoring cabin environment...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }
}
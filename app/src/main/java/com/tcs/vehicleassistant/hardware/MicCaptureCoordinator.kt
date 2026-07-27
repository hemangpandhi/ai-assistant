package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.assistant.ui.assistant.api.AssistantRuntime
import com.tcs.vehicleassistant.WakeWordService
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.service.VehicleAgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Arms command STT as early as possible (hotword / assist icon), before the
 * immersive overlay finishes composing. Capture is independent of LLM warm-up.
 *
 * Flow: stop Vosk → await mic release → ensure warm [SpeechRecognizer] →
 * [IAudioManager.startListening] → overlay attaches to an already-open ear.
 */
object MicCaptureCoordinator {
    private const val TAG = "MicCapture"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var isPreArmed: Boolean = false
        private set

    @Volatile
    private var arming: Boolean = false

    /** True while a pre-arm job is in flight or STT was started ahead of the overlay. */
    fun isCaptureLive(audio: IAudioManager?): Boolean {
        if (isPreArmed || arming) return true
        return audio?.isActivelyListening() == true
    }

    fun clearSessionArm() {
        isPreArmed = false
        arming = false
    }

    /**
     * Call at the earliest summon signal (hotword match or assist icon), ideally
     * before [android.service.voice.VoiceInteractionSession] shows.
     */
    fun preArm(context: Context, reason: String) {
        val app = context.applicationContext
        if (isPreArmed || arming) {
            AssistantDebugLog.d(TAG, "preArm skip — already armed/arming ($reason)")
            return
        }
        arming = true
        AssistantDebugLog.d(TAG, "preArm begin ($reason)")

        // Free the mic from wake-word ASAP.
        runCatching {
            val stop = Intent(app, WakeWordService::class.java).apply {
                action = "ACTION_STOP_LISTENING"
            }
            app.startService(stop)
        }

        // Bring up agent service so Koin singles / ViewModel listeners exist.
        runCatching {
            val agent = Intent(app, VehicleAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(agent)
            } else {
                app.startService(agent)
            }
        }.onFailure {
            Log.w(TAG, "Could not start VehicleAgentService early", it)
        }

        scope.launch {
            try {
                val released = WakeWordService.awaitMicReleased(timeoutMs = 900L)
                AssistantDebugLog.d(TAG, "wake mic released=$released holding=${WakeWordService.isHoldingMic}")
                // Brief settle after AudioRecord teardown.
                delay(25L)
                withContext(Dispatchers.Main.immediate) {
                    openEar(reason)
                }
            } catch (t: Throwable) {
                AssistantDebugLog.e(TAG, "preArm failed: ${t.message}")
                arming = false
            }
        }
    }

    private fun openEar(reason: String) {
        try {
            val koin = getKoin()
            val audio = koin.get<IAudioManager>()
            // Force ViewModel init so RecognitionListener callbacks are wired
            // before the first partial/final arrives.
            val vm = koin.get<AssistantViewModel>()
            // Attach backend collectors BEFORE startListening so liveTranscript is observed.
            com.assistant.ui.assistant.api.AssistantRuntime.backend
                ?.asMicController()
                ?.attachSession(vm, audio)
            audio.ensureWarmRecognizer()
            audio.requestAssistantDuck()
            if (audio.isActivelyListening()) {
                isPreArmed = true
                arming = false
                AssistantDebugLog.d(TAG, "ear already open ($reason)")
                return
            }
            audio.startListening()
            isPreArmed = true
            arming = false
            AssistantDebugLog.d(TAG, "ear open — startListening ($reason)")
        } catch (t: Throwable) {
            arming = false
            isPreArmed = false
            AssistantDebugLog.e(TAG, "openEar failed: ${t.message}")
        }
    }
}
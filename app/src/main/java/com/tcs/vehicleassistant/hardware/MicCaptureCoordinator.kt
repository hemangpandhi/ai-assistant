package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.tcs.vehicleassistant.WakeWordService
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.service.VehicleAgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.atomic.AtomicInteger

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
    private val armToken = AtomicInteger(0)
    private var armJob: Job? = null

    @Volatile
    var isPreArmed: Boolean = false
        private set

    @Volatile
    private var arming: Boolean = false

    /**
     * True while a pre-arm job is in flight, or STT is actually Starting/Listening.
     * Never trusts a stale [isPreArmed] flag alone (that caused "already capturing" skips
     * after ERROR_CLIENT left the recognizer Idle).
     */
    fun isCaptureLive(audio: IAudioManager?): Boolean {
        if (arming) return true
        val live = audio?.isActivelyListening() == true
        if (!live && isPreArmed) {
            isPreArmed = false
        }
        return live
    }

    fun clearSessionArm() {
        armToken.incrementAndGet()
        armJob?.cancel()
        armJob = null
        isPreArmed = false
        arming = false
    }

    /**
     * Call at the earliest summon signal (hotword match or assist icon), ideally
     * before [android.service.voice.VoiceInteractionSession] shows.
     */
    fun preArm(context: Context, reason: String) {
        val app = context.applicationContext
        if (arming) {
            AssistantDebugLog.d(TAG, "preArm skip — already arming ($reason)")
            return
        }
        val audioHint = runCatching { getKoin().get<IAudioManager>() }.getOrNull()
        if (audioHint?.isReadyListening() == true) {
            isPreArmed = true
            AssistantDebugLog.d(TAG, "preArm skip — already ready ($reason)")
            return
        }
        // Stale optimistic arm from a failed start — clear and proceed.
        isPreArmed = false
        arming = true
        val token = armToken.incrementAndGet()
        AssistantDebugLog.d(TAG, "preArm begin ($reason) token=$token")

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

        armJob?.cancel()
        armJob = scope.launch {
            try {
                val released = WakeWordService.awaitMicReleased(timeoutMs = 900L)
                AssistantDebugLog.d(TAG, "wake mic released=$released holding=${WakeWordService.isHoldingMic}")
                if (!released && WakeWordService.isHoldingMic) {
                    AssistantDebugLog.w(TAG, "preArm abort — wake still holding mic")
                    if (token == armToken.get()) {
                        arming = false
                        isPreArmed = false
                    }
                    return@launch
                }
                // Brief settle after AudioRecord teardown.
                delay(40L)
                if (token != armToken.get()) {
                    AssistantDebugLog.d(TAG, "preArm cancelled before openEar")
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    if (token != armToken.get()) return@withContext
                    openEar(reason, token)
                }
            } catch (t: Throwable) {
                AssistantDebugLog.e(TAG, "preArm failed: ${t.message}")
                if (token == armToken.get()) {
                    arming = false
                    isPreArmed = false
                }
            }
        }
    }

    private fun openEar(reason: String, token: Int) {
        if (token != armToken.get()) return
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
            if (audio.isReadyListening()) {
                isPreArmed = true
                arming = false
                AssistantDebugLog.d(TAG, "ear already ready ($reason)")
                return
            }
            if (audio.isActivelyListening()) {
                // Starting — wait for ready via backend; mark arming done.
                isPreArmed = true
                arming = false
                AssistantDebugLog.d(TAG, "ear already starting ($reason)")
                return
            }
            audio.startListening()
            // Optimistic until ready/error; [isCaptureLive] falls back to audio phase.
            isPreArmed = true
            arming = false
            AssistantDebugLog.d(TAG, "ear open — startListening ($reason)")
        } catch (t: Throwable) {
            if (token == armToken.get()) {
                arming = false
                isPreArmed = false
            }
            AssistantDebugLog.e(TAG, "openEar failed: ${t.message}")
        }
    }
}

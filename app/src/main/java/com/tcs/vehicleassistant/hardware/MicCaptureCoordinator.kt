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
 * Prepares the mic handoff as early as possible (hotword / assist icon).
 *
 * Does NOT call startListening — [VehicleAgentAssistantBackend] is the sole STT owner.
 * Flow: stop Vosk → await release → warm SpeechRecognizer + attach listeners.
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

    /** True while Vosk stop + recognizer warm-up is still running. */
    fun isWarmingUp(): Boolean = arming

    /** True while Vosk handoff / warm-up is in flight, or STT is Starting/Listening. */
    fun isCaptureLive(audio: IAudioManager?): Boolean {
        if (arming) return true
        return audio?.isActivelyListening() == true || audio?.isReadyListening() == true
    }

    fun clearSessionArm() {
        armToken.incrementAndGet()
        armJob?.cancel()
        armJob = null
        isPreArmed = false
        arming = false
    }

    /**
     * Call at the earliest summon signal (hotword match or assist icon).
     * Only frees Vosk + warms STT — never starts recognition.
     */
    fun preArm(context: Context, reason: String) {
        val app = context.applicationContext
        // Newer summon supersedes an in-flight warm (token bump cancels old job).
        if (arming) {
            AssistantDebugLog.d(TAG, "preArm supersede — was arming ($reason)")
        }
        isPreArmed = false
        arming = true
        val token = armToken.incrementAndGet()
        AssistantDebugLog.d(TAG, "preArm begin ($reason) token=$token")

        runCatching {
            val stop = Intent(app, WakeWordService::class.java).apply {
                action = "ACTION_STOP_LISTENING"
            }
            app.startService(stop)
        }

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
                    AssistantDebugLog.w(TAG, "preArm — wake still holding, continue warm anyway")
                }
                delay(60L)
                if (token != armToken.get()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (token != armToken.get()) return@withContext
                    warmOnly(reason, token)
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

    private fun warmOnly(reason: String, token: Int) {
        if (token != armToken.get()) return
        try {
            val koin = getKoin()
            val audio = koin.get<IAudioManager>()
            val vm = koin.get<AssistantViewModel>()
            com.assistant.ui.assistant.api.AssistantRuntime.backend
                ?.asMicController()
                ?.attachSession(vm, audio)
            audio.ensureWarmRecognizer()
            audio.requestAssistantDuck()
            isPreArmed = true
            arming = false
            AssistantDebugLog.d(TAG, "preArm warm done — backend owns startListening ($reason)")
        } catch (t: Throwable) {
            if (token == armToken.get()) {
                arming = false
                isPreArmed = false
            }
            AssistantDebugLog.e(TAG, "warmOnly failed: ${t.message}")
        }
    }
}

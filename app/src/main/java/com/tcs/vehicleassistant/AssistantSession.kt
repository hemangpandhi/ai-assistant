package com.tcs.vehicleassistant

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.assistant.ui.assistant.api.AssistantRuntime
import com.assistant.ui.assistant.ui.theme.AssistantTheme
import com.assistant.ui.assistant.entry.VirtualAssistantOverlay
import com.assistant.ui.assistant.ui.immersive.AssistantUiLatency
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantDismiss
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantSummon
import com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin
import com.tcs.vehicleassistant.assistant.AssistantIdleTimeout
import com.tcs.vehicleassistant.assistant.AssistantUiMode
import com.tcs.vehicleassistant.assistant.AssistantUiProfile
import com.tcs.vehicleassistant.assistant.VehicleCabinContextStore
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.ViewModelEvent
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.service.VehicleAgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.assistant.ui.assistant.api.AssistantBackend

/**
 * Lifecycle / SavedState / ViewModel host for Compose content inside a
 * [VoiceInteractionSession] (which is not itself a LifecycleOwner).
 *
 * SavedStateRegistry's Restarter may only be registered while the owner is still
 * [Lifecycle.State.INITIALIZED] — jumping straight to RESUMED before
 * [SavedStateRegistryController.performRestore] crashes with:
 * "Restarter must be created only during owner's initialization stage".
 */
private class SessionComposeHost :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        // Attach + restore while still INITIALIZED (LifecycleRegistry default).
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED ||
            registry.currentState == Lifecycle.State.DESTROYED
        ) {
            store.clear()
            return
        }
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

/**
 * System voice-interaction session.
 *
 * Default renderer: Compose immersive assistant (design module) via [AssistantUiProfile].
 * Legacy XML voice plates remain available through ADB (`ui=xml:…`).
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    private lateinit var overlayView: View
    private var statusText: TextView? = null
    private var responseText: TextView? = null
    private var etInput: EditText? = null
    private var btnSend: Button? = null
    private var btnMic: ImageButton? = null
    private var btnOpenApp: Button? = null
    private var inputControls: View? = null
    private var voiceAnimation: VoiceAnimationView? = null
    private var svResponse: android.widget.ScrollView? = null

    private var audioManager: IAudioManager? = null
    private var viewModel: AssistantViewModel? = null
    private var agentService: VehicleAgentService? = null
    private var isBound = false
    private var usingComposeUi = true
    private var currentUiToken: String = ""
    private var currentLayoutStyle = -1
    private var composeHost: SessionComposeHost? = null
    /** True between [onShow] and [onHide] — used to dismiss when another system-bar UI opens. */
    private var sessionUiVisible = false
    private var baselineResumedActivity: String? = null
    private var baselineTopPackage: String? = null
    private var focusListenerRegistered = false
    private var topTaskPollJob: Job? = null
    private var closeSystemDialogsReceiverRegistered = false

    private var dotAnimatorJob: Job? = null
    private var typewriterJob: Job? = null
    private var targetDisplayMessage = ""
    private var currentDisplayLength = 0
    private val typingSpeedMs: Long = 15L
    private var unloadJob: Job? = null
    /** Countdown that closes the overlay after quiet listening. */
    private var idleJob: Job? = null
    /** Collects uiState / events to arm or pause [idleJob]. */
    private var idleWatchJob: Job? = null
    /**
     * Idle auto-close must not run until STT has reached Listening at least once.
     * Otherwise the 5s timer races wake-word mic release and closes before the user can speak.
     */
    private var idleArmedAfterMicReady = false
    /** Opens agent STT after wake-word AudioRecord is released. */
    private var micHandoffJob: Job? = null

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val activityWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) {
            // Same-process apps (e.g. LocalLLMActivity) — dismiss as soon as they start.
            if (!sessionUiVisible) return
            if (a.javaClass.name.contains("AssistantSession")) return
            AssistantDebugLog.d("Session", "activity started=${a.javaClass.simpleName} — dismiss")
            dismissForExternalUi("activity-started:${a.javaClass.simpleName}")
        }
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityResumed(a: Activity) {
            if (!sessionUiVisible) return
            val name = a.javaClass.name
            if (baselineResumedActivity == null) {
                baselineResumedActivity = name
                AssistantDebugLog.d("Session", "baseline activity=$name")
                return
            }
            if (name != baselineResumedActivity) {
                dismissForExternalUi("activity-resumed:$name")
            }
        }
    }

    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (!sessionUiVisible) return@OnWindowFocusChangeListener
        AssistantDebugLog.d("Session", "window focus=$hasFocus")
        if (!hasFocus) {
            if (isSummonProtected()) {
                AssistantDebugLog.d("Session", "focus-lost ignored (summon protect)")
                return@OnWindowFocusChangeListener
            }
            observerScope.launch {
                delay(80)
                if (sessionUiVisible && ::overlayView.isInitialized &&
                    !overlayView.hasWindowFocus() &&
                    !isSummonProtected()
                ) {
                    dismissForExternalUi("window-focus-lost")
                }
            }
        }
    }

    private val closeSystemDialogsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!sessionUiVisible) return
            val reason = intent?.getStringExtra("reason") ?: intent?.action ?: "unknown"
            AssistantDebugLog.d("Session", "CLOSE_SYSTEM_DIALOGS reason=$reason")
            if (isSummonProtected()) {
                AssistantDebugLog.d("Session", "close-system-dialogs ignored (summon protect)")
                return
            }
            // Home / recent / system bar often fires this when leaving the assistant.
            dismissForExternalUi("close-system-dialogs:$reason")
        }
    }

    private fun dismissForExternalUi(reason: String) {
        AssistantDebugLog.d("Session", "dismiss overlay ($reason) visible=$sessionUiVisible")
        val wasVisible = sessionUiVisible
        sessionUiVisible = false
        if (wasVisible) {
            runCatching { hide() }
        }
        // Some AAOS builds keep the session window until finish() — always tear it down
        // even if hide() already cleared sessionUiVisible (Compose LaunchIntent race).
        observerScope.launch {
            delay(50)
            runCatching { finish() }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VehicleAgentService.LocalBinder
            agentService = binder.getService()
            isBound = true
            viewModel = agentService?.viewModel
            audioManager = agentService?.audioManager
            AssistantRuntime.backend?.asMicController()?.attachSession(
                viewModel,
                audioManager,
            )
            // Duck as soon as the agent audio manager is ready (may have missed onShow).
            if (sessionUiVisible) {
                audioManager?.requestAssistantDuck()
            }
            if (usingComposeUi) {
                startObservingComposeAgentEvents()
            } else {
                startObservingViewModel()
            }
            if (sessionUiVisible) {
                startIdleWatch()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            AssistantRuntime.backend?.asMicController()?.detachSession()
            isBound = false
            agentService = null
            viewModel = null
            audioManager = null
        }
    }

    override fun onHide() {
        super.onHide()
        audioManager?.stopListening()
        audioManager?.stopSpeaking()

        observerScope.launch {
            // Let SpeechRecognizer.destroy() finish before Vosk grabs AudioRecord.
            delay(450)
            val restartIntent = Intent(context, WakeWordService::class.java)
            restartIntent.action = "ACTION_RESTART_LISTENING"
            context.startService(restartIntent)
        }

        unloadJob?.cancel()
        
        // Smart Memory Management: Only unload the heavy LLM from RAM if the car is parked.
        // If the car is driving (or in any other gear), we keep it loaded so it's instantly available.
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600 || android.os.Build.DEVICE.contains("tangorpro", ignoreCase = true)
        if (!isTablet && VehicleManager.getGearSelection() == "Park") {
             LLMManager.unload()
        }
    }

    override fun onCreateContentView(): View {
        // Paint the Compose stage first — every millisecond before setContentView
        // is cold-start latency the driver feels after install.
        AssistantUiLatency.markContentViewStart()
        AssistantUiProfile.install(context)
        inflateContentForProfile()
        AssistantUiLatency.mark("setContentView done")

        // Bind agent + warm deps after the first pre-draw so TTS/Car init cannot
        // contend with the Compose first frame (target < 100ms TTFF).
        if (::overlayView.isInitialized) {
            val vto = overlayView.viewTreeObserver
            vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    overlayView.viewTreeObserver.removeOnPreDrawListener(this)
                    AssistantUiLatency.mark("first pre-draw")
                    bindAgentService()
                    warmSessionDependencies()
                    return true
                }
            })
        } else {
            bindAgentService()
            warmSessionDependencies()
        }
        return overlayView
    }

    private fun bindAgentService() {
        if (isBound) return
        val intent = Intent(context, VehicleAgentService::class.java)
        runCatching {
            context.startForegroundService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun inflateAndBindLayout() {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var layoutStyle = prefs.getInt("ui_layout_pref", -1)
        if (layoutStyle == -1) {
            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600 || android.os.Build.DEVICE.contains("tangorpro", ignoreCase = true)
            layoutStyle = if (isTablet) 1 else 0
            prefs.edit().putInt("ui_layout_pref", layoutStyle).apply()
        }
        currentLayoutStyle = layoutStyle
        composeHost?.destroy()
        composeHost = null

        val layoutRes = when (layoutStyle) {
            0 -> R.layout.assistant_overlay
            1 -> R.layout.assistant_overlay_pill
            2 -> R.layout.assistant_overlay_side
            3 -> R.layout.assistant_overlay_top
            4 -> R.layout.assistant_overlay_immersive
            5 -> R.layout.assistant_overlay_hud
            6 -> R.layout.assistant_overlay_beveled
            7 -> R.layout.assistant_overlay_cinematic
            else -> R.layout.assistant_overlay
        }
        overlayView = layoutInflater.inflate(layoutRes, null)
        statusText = overlayView.findViewById(R.id.assistantResponseText)
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)
        btnMic = overlayView.findViewById(R.id.btnMic)
        btnOpenApp = overlayView.findViewById(R.id.btnOpenApp)
        svResponse = overlayView.findViewById(R.id.svResponse)
        inputControls = overlayView.findViewById(R.id.inputControlsContainer)
        voiceAnimation = overlayView.findViewById(R.id.voiceAnimation)

        val modelInfoTag: TextView? = overlayView.findViewById(R.id.modelInfoTag)
        modelInfoTag?.text = com.tcs.vehicleassistant.assistant.AssistantLlmDebugLabels.modelLabel()

        val activeBackendTag: TextView? = overlayView.findViewById(R.id.activeBackendTag)
        activeBackendTag?.text = com.tcs.vehicleassistant.assistant.AssistantLlmDebugLabels.backendLabel()

        responseText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val len = s?.length ?: 0
                responseText?.gravity = if (len < 50) {
                    android.view.Gravity.CENTER
                } else {
                    android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                }
            }
        })

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            overlayView.findViewById<View>(R.id.blurBackgroundView)?.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    25f,
                    25f,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }

        overlayView.findViewById<View>(R.id.rootOverlay)?.setOnClickListener { hide() }

        btnOpenApp?.setOnClickListener {
            val intent = Intent(context, LocalLLMActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            hide()
        }

        btnSend?.setOnClickListener {
            val query = etInput?.text?.toString().orEmpty()
            if (query.isNotBlank()) {
                noteUserActivity()
                audioManager?.stopSpeaking()
                resetDisplayState()
                viewModel?.handleQuery(query)
                etInput?.setText("")
            }
        }

        btnMic?.setOnClickListener {
            if (viewModel?.isProcessing() == true) {
                android.util.Log.w("AssistantSession", "Ignoring mic trigger because query is still being processed.")
                return@setOnClickListener
            }
            noteUserActivity()
            LatencyLogger.reset()
            LatencyLogger.log("AssistantSession", "Voice Button Clicked")
            audioManager?.stopSpeaking()
            btnMic?.isEnabled = false
            LatencyLogger.log("AssistantSession", "Speech Recognizer startListening() called")
            try {
                audioManager?.startListening()
            } catch (e: Exception) {
                LatencyLogger.log("AssistantSession", "Error starting speech recognizer: ${e.message}")
                stopDotAnimation("Error starting microphone.")
                statusText?.visibility = View.VISIBLE
                voiceAnimation?.state = VoiceAnimationView.State.IDLE
            }
            btnMic?.isEnabled = true
        }

        etInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) noteUserActivity()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setContentView(overlayView)
    }

    /**
     * Quiet-listening auto-close: after [AssistantIdleTimeout] seconds with no speech /
     * LLM / text activity, dismiss the overlay and finish the session.
     *
     * Busy states (thinking / streaming / speaking) and model warm-up pause the timer.
     * Timer does not start until the mic has reached Listening at least once.
     */
    private fun startIdleWatch() {
        AssistantIdleTimeout.install(context)
        idleArmedAfterMicReady = false
        idleWatchJob?.cancel()
        idleWatchJob = observerScope.launch {
            val vm = viewModel
            if (vm != null) {
                launch {
                    vm.uiState.collect { state -> onUiStateForIdle(state) }
                }
                launch {
                    vm.events.collect { event ->
                        when (event) {
                            is ViewModelEvent.SetInputText -> {
                                if (event.text.isNotBlank()) noteUserActivity()
                            }
                            is ViewModelEvent.StartListening -> {
                                idleArmedAfterMicReady = true
                                armIdleTimer("start-listening")
                            }
                            else -> Unit
                        }
                    }
                }
            }
            // Do not count down until STT is ready — pause while warming / handoff.
            pauseIdleTimer()
            AssistantDebugLog.d("Session", "idle watch started — waiting for mic ready")
            // Safety: if STT never reaches Listening, still allow close after a long grace.
            delay(15_000)
            if (isActive && sessionUiVisible && !idleArmedAfterMicReady) {
                AssistantDebugLog.w("Session", "idle grace — mic never ready, arming anyway")
                idleArmedAfterMicReady = true
                armIdleTimer("mic-ready-fallback")
            }
        }
    }

    private fun cancelIdleWatch() {
        idleJob?.cancel()
        idleJob = null
        idleWatchJob?.cancel()
        idleWatchJob = null
        micHandoffJob?.cancel()
        micHandoffJob = null
        idleArmedAfterMicReady = false
    }

    private fun onUiStateForIdle(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Thinking,
            is AssistantUiState.Streaming,
            is AssistantUiState.Speaking,
            -> pauseIdleTimer()
            is AssistantUiState.Listening -> {
                idleArmedAfterMicReady = true
                if (isModelWarmingUp()) {
                    pauseIdleTimer()
                } else {
                    armIdleTimer("ui:Listening")
                }
            }
            is AssistantUiState.Idle,
            is AssistantUiState.Error,
            -> {
                if (!idleArmedAfterMicReady || isModelWarmingUp()) {
                    pauseIdleTimer()
                } else {
                    armIdleTimer("ui:${state::class.simpleName}")
                }
            }
        }
    }

    private fun isModelWarmingUp(): Boolean {
        if (LocalLLMActivity.isCloudModelActive) return false
        return LLMManager.isInitializing || LLMManager.isPrewarming || !LLMManager.isReady()
    }

    /** Reset the idle countdown after real user / pipeline activity. */
    private fun noteUserActivity() {
        if (!sessionUiVisible) return
        if (viewModel?.isProcessing() == true) {
            pauseIdleTimer()
            return
        }
        if (!idleArmedAfterMicReady) return
        armIdleTimer("user-activity")
    }

    private fun pauseIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun armIdleTimer(reason: String) {
        idleJob?.cancel()
        if (!sessionUiVisible) return
        if (!idleArmedAfterMicReady) {
            AssistantDebugLog.d("Session", "idle arm skipped — mic not ready ($reason)")
            return
        }
        val timeoutMs = AssistantIdleTimeout.currentMs()
        if (timeoutMs <= 0L) {
            AssistantDebugLog.d("Session", "idle timer disabled ($reason)")
            idleJob = null
            return
        }
        idleJob = observerScope.launch {
            AssistantDebugLog.d("Session", "idle arm ${timeoutMs}ms ($reason)")
            delay(timeoutMs)
            if (!isActive || !sessionUiVisible) return@launch
            // Still busy? Skip close (e.g. race with Thinking).
            val busy = when (viewModel?.uiState?.value) {
                is AssistantUiState.Thinking,
                is AssistantUiState.Streaming,
                is AssistantUiState.Speaking,
                -> true
                else -> viewModel?.isProcessing() == true
            }
            if (busy || isModelWarmingUp()) {
                AssistantDebugLog.d("Session", "idle fire skipped — busy")
                return@launch
            }
            // Never close while STT is still starting (not yet Listening).
            if (viewModel?.uiState?.value !is AssistantUiState.Listening &&
                viewModel?.uiState?.value !is AssistantUiState.Idle &&
                viewModel?.uiState?.value !is AssistantUiState.Error
            ) {
                return@launch
            }
            dismissForIdleTimeout()
        }
    }

    private fun dismissForIdleTimeout() {
        AssistantDebugLog.d("Session", "idle-timeout — closing overlay + assistant")
        if (usingComposeUi) {
            // Play Compose exit animation (AssistantTokens.ExitMs ≈ 280), then tear down.
            notifyImmersiveAssistantDismiss()
            observerScope.launch {
                delay(320L)
                dismissForExternalUi("idle-timeout")
            }
        } else {
            dismissForExternalUi("idle-timeout")
        }
    }

    private fun startObservingComposeAgentEvents() {
        // Compose face/transcript come from AssistantBackend events; this only
        // handles host-side intents the overlay cannot start itself.
        observerScope.launch {
            viewModel?.events?.collect { event ->
                when (event) {
                    is ViewModelEvent.LaunchIntent -> {
                        try {
                            event.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            this@AssistantSession.startVoiceActivity(event.intent)
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "startVoiceActivity failed", e)
                            try {
                                event.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.applicationContext.startActivity(event.intent)
                            } catch (e2: Exception) {
                                android.util.Log.e("AssistantSession", "Fallback startActivity failed", e2)
                            }
                        }
                        // Match XML path: dismissForExternalUi owns hide()+finish().
                        // Calling hide() first cleared sessionUiVisible and skipped finish().
                        dismissForExternalUi("compose-launch-intent")
                    }
                    is ViewModelEvent.ShowToast ->
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    else -> Unit
                }
            }
        }
    }

    private fun startObservingViewModel() {
        if (viewModel == null || usingComposeUi) return

        viewModel?.resetUiState()

        observerScope.launch {
            viewModel?.uiState?.collect { state ->
                when (state) {
                    is AssistantUiState.Idle -> {
                        voiceAnimation?.state = VoiceAnimationView.State.IDLE
                        stopDotAnimation()
                    }
                    is AssistantUiState.Listening -> {
                        statusText?.visibility = View.VISIBLE
                        startDotAnimation("")
                        voiceAnimation.state = VoiceAnimationView.State.LISTENING
                        if (state.partialText.isNotEmpty()) {
                            responseText.text = "\"${state.partialText}\""
                            responseText.gravity = android.view.Gravity.CENTER
                        } else {
                            responseText.text = ""
                        }
                    }
                    is AssistantUiState.Thinking -> {
                        resetDisplayState()
                        statusText?.visibility = View.VISIBLE
                        startDotAnimation("")
                        voiceAnimation.state = VoiceAnimationView.State.THINKING
                        if (!state.userQuery.isNullOrBlank()) {
                            responseText.text = "\"${state.userQuery}\""
                            responseText.gravity = android.view.Gravity.CENTER
                        }
                    }
                    is AssistantUiState.Streaming -> {
                        if (statusText?.visibility == View.VISIBLE) {
                            stopDotAnimation()
                            voiceAnimation?.state = VoiceAnimationView.State.SPEAKING
                        }
                        targetDisplayMessage = state.displayText
                        startTypewriterIfNeeded()
                    }
                    is AssistantUiState.Speaking -> {
                        voiceAnimation?.state = VoiceAnimationView.State.SPEAKING
                        stopDotAnimation()
                        targetDisplayMessage = state.finalMessage
                        startTypewriterIfNeeded()
                    }
                    is AssistantUiState.Error -> {
                        voiceAnimation?.state = VoiceAnimationView.State.IDLE
                        stopDotAnimation()
                        responseText?.text = state.errorMessage
                    }
                }
            }
        }

        observerScope.launch {
            viewModel?.events?.collect { event ->
                when (event) {
                    is ViewModelEvent.LaunchIntent -> {
                        try {
                            event.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.applicationContext.startActivity(event.intent)
                            
                            // Optionally hide the assistant UI so the newly launched app is visible
                            this@AssistantSession.hide()
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "startActivity failed for intent: ${event.intent}", e)
                        }
                        dismissForExternalUi("xml-launch-intent")
                    }
                    is ViewModelEvent.FinishSession -> finish()
                    is ViewModelEvent.StartListening -> btnMic?.performClick()
                    is ViewModelEvent.ShowToast ->
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    is ViewModelEvent.SetInputEnabled -> {
                        btnSend?.isEnabled = event.enabled
                        btnMic?.isEnabled = event.enabled
                    }
                    is ViewModelEvent.SetInputText -> etInput?.setText(event.text)
                    is ViewModelEvent.AffectiveMood -> Unit // Compose path owns face mood
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        // Assist / system-bar icon while already open → toggle closed (with Compose exit anim).
        if (sessionUiVisible) {
            AssistantDebugLog.d("Session", "onShow while visible — toggle dismiss")
            super.onShow(args, showFlags)
            if (usingComposeUi) {
                notifyImmersiveAssistantDismiss()
            } else {
                runCatching { hide() }
            }
            return
        }

        super.onShow(args, showFlags)
        AssistantDebugLog.d("Session", "onShow flags=$showFlags compose=$usingComposeUi")
        sessionUiVisible = true
        beginSummonProtection()
        baselineResumedActivity = null
        baselineTopPackage = null
        registerDismissWatchers()
        startIdleWatch()

        // Duck music immediately — do not wait for STT (~1.4s) or service bind.
        audioManager?.requestAssistantDuck() ?: requestFallbackDuck()

        window?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        unloadJob?.cancel()
        AssistantUiProfile.install(context)
        VehicleCabinContextStore.publishFromVehicleManager()

        val mode = AssistantUiProfile.current()
        if (mode.adbToken != currentUiToken) {
            inflateContentForProfile()
            if (!usingComposeUi && viewModel != null) {
                startObservingViewModel()
            }
        } else if (!usingComposeUi && mode is AssistantUiMode.Xml && mode.layoutIndex != currentLayoutStyle) {
            inflateAndBindLayout(mode.layoutIndex)
            if (viewModel != null) startObservingViewModel()
        }

        val stopListeningIntent = Intent(context, WakeWordService::class.java)
        stopListeningIntent.action = "ACTION_STOP_LISTENING"
        context.startService(stopListeningIntent)

        if (!LLMManager.isReady() || LLMManager.isInitializing) {
            statusText.text = "Initializing Model..."
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.GONE
            btnMic.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
                // Ensure initialization is started if not already
                LLMManager.autoInitialize(context.applicationContext)
                
                // Wait until LLMManager engine is ready (don't block on prewarm - it runs in background)
                withContext(Dispatchers.IO) {
                    var waitCount = 0
                    while (!LLMManager.isReady() || LLMManager.isInitializing) {
                        if (waitCount % 2 == 0) {
                            android.util.Log.d("AssistantSession", "Waiting for LLM. isReady=${LLMManager.isReady()}, isInit=${LLMManager.isInitializing}, engineNull=${!LLMManager.isReady()}")
                        }
                        delay(500)
                        waitCount++
                    }
                }
                statusText.text = "Hi, how can I help you?"
                inputControls.visibility = View.VISIBLE
                btnSend.isEnabled = true
                btnMic.isEnabled = true
                
                // Automatically start listening if invoked via voice match/hotword
                if (showFlags and SHOW_WITH_ASSIST != 0) {
                    delay(500) // Wait for WakeWordService to release the mic
                    btnMic.performClick()
                }
            }
        } else {
            statusText?.visibility = View.VISIBLE
            statusText?.text = "Hi, how can I help you?"
            btnOpenApp?.visibility = View.GONE
            inputControls?.visibility = View.VISIBLE
            btnSend?.isEnabled = true
            btnMic?.isEnabled = true
            armIdleTimer("xml-ready")

            if (showFlags and SHOW_WITH_ASSIST != 0) {
                observerScope.launch {
                    delay(500)
                    btnMic?.performClick()
                }
            }
        }
    }

    private fun resetDisplayState() {
        responseText?.text = ""
        targetDisplayMessage = ""
        currentDisplayLength = 0
        typewriterJob?.cancel()
    }

    private fun startTypewriterIfNeeded() {
        // If the target message doesn't start with what we've currently typed, reset the length to 0 to restart the typewriter smoothly.
        if (targetDisplayMessage.isNotEmpty() && currentDisplayLength > 0 && !targetDisplayMessage.startsWith(responseText.text.toString())) {
            typewriterJob?.cancel()
            currentDisplayLength = 0
            responseText.text = ""
        }

        if (typewriterJob == null || typewriterJob?.isActive != true) {
            typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                val startTime = System.currentTimeMillis()
                
                while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                    val timeSinceTts = System.currentTimeMillis() - (viewModel?.lastTtsUpdateTime ?: 0L)
                    val isTtsActive = (viewModel?.lastTtsUpdateTime ?: 0L) > 0L && timeSinceTts < 2000
                    
                    // Cap the text display length so it doesn't vastly outpace the spoken TTS text.
                    if (isTtsActive) {
                        val reportedSpokenLength = viewModel?.ttsSpokenLength ?: 0
                        // Fallback: estimate 20 chars per second if the TTS engine doesn't report range updates
                        val simulatedSpokenLength = ((System.currentTimeMillis() - startTime) / 1000f * 20).toInt()
                        val effectiveSpokenLength = Math.max(reportedSpokenLength, simulatedSpokenLength)
                        
                        if (currentDisplayLength > effectiveSpokenLength + 5) {
                            delay(16) // 1 frame wait (vsync aligned)
                            continue
                        }
                    }
                    currentDisplayLength = Math.min(currentDisplayLength + 1, targetDisplayMessage.length)
                    responseText?.text = targetDisplayMessage.substring(0, currentDisplayLength)
                    if (currentDisplayLength % 5 == 0) {
                        svResponse?.post { svResponse?.fullScroll(View.FOCUS_DOWN) }
                    }
                    delay(typingSpeedMs)
                }
                if (targetDisplayMessage.isNotEmpty()) {
                    responseText?.text = parseMarkdown(targetDisplayMessage)
                }
            }
        }
    }

    private fun startDotAnimation(baseText: String) {
        dotAnimatorJob?.cancel()
        if (baseText.isEmpty()) {
            statusText?.text = ""
            return
        }
        dotAnimatorJob = observerScope.launch {
            var dotCount = 0
            while (isActive) {
                statusText?.text = "$baseText${".".repeat(dotCount)}"
                dotCount = (dotCount + 1) % 4
                delay(400)
            }
        }
    }

    private fun registerDismissWatchers() {
        val app = context.applicationContext as? Application ?: return
        runCatching { app.unregisterActivityLifecycleCallbacks(activityWatcher) }
        app.registerActivityLifecycleCallbacks(activityWatcher)
        if (!focusListenerRegistered && ::overlayView.isInitialized) {
            overlayView.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
            focusListenerRegistered = true
        }
        if (!closeSystemDialogsReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            runCatching {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    closeSystemDialogsReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                closeSystemDialogsReceiverRegistered = true
            }.onFailure {
                AssistantDebugLog.w("Session", "CLOSE_SYSTEM_DIALOGS register failed: ${it.message}")
            }
        }
        startTopTaskPoller()
    }

    private fun unregisterDismissWatchers() {
        topTaskPollJob?.cancel()
        topTaskPollJob = null
        val app = context.applicationContext as? Application
        runCatching { app?.unregisterActivityLifecycleCallbacks(activityWatcher) }
        if (focusListenerRegistered && ::overlayView.isInitialized) {
            runCatching {
                overlayView.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
            }
            focusListenerRegistered = false
        }
        if (closeSystemDialogsReceiverRegistered) {
            runCatching {
                context.applicationContext.unregisterReceiver(closeSystemDialogsReceiver)
            }
            closeSystemDialogsReceiverRegistered = false
        }
    }

    /**
     * Cross-process dismiss: ActivityLifecycleCallbacks only see *this* app's activities.
     * System-bar launches (Maps, phone, …) live in other processes — poll the foreground
     * task/package and hide when it changes.
     */
    private fun startTopTaskPoller() {
        topTaskPollJob?.cancel()
        topTaskPollJob = observerScope.launch {
            // Let the session settle before sampling the baseline top package.
            delay(350)
            while (isActive && sessionUiVisible) {
                val top = foregroundPackage()
                if (top != null) {
                    if (baselineTopPackage == null) {
                        baselineTopPackage = top
                        AssistantDebugLog.d("Session", "baseline topPkg=$top")
                    } else if (top != baselineTopPackage && !isTransientSystemPackage(top)) {
                        // Confirm once — AAOS system UI can briefly report a different pkg.
                        delay(120)
                        val confirmed = foregroundPackage()
                        if (confirmed == top && confirmed != baselineTopPackage && sessionUiVisible) {
                            if (isSummonProtected()) {
                                AssistantDebugLog.d(
                                    "Session",
                                    "topPkg change ignored (summon protect): $confirmed",
                                )
                            } else {
                                dismissForExternalUi("top-pkg $baselineTopPackage → $confirmed")
                                break
                            }
                        }
                    }
                }
                delay(250)
            }
        }
    }

    /** Packages that flicker without meaning a real app launch from the system bar. */
    private fun isTransientSystemPackage(pkg: String): Boolean {
        return pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg.endsWith(".systemui") ||
            pkg.contains("permissioncontroller")
    }

    private fun foregroundPackage(): String? {
        // 1) Running tasks (works for priv-apps with REAL_GET_TASKS on AAOS).
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val pkg = am.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
            if (!pkg.isNullOrBlank()) return pkg
        }
        // 2) Importance-based process list.
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val proc = am.runningAppProcesses?.firstOrNull {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
            val pkg = proc?.pkgList?.firstOrNull() ?: proc?.processName
            if (!pkg.isNullOrBlank()) return pkg
        }
        // 3) Usage stats (if granted).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 15_000, end)
                val top = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
                if (!top.isNullOrBlank()) return top
            }
        }
        return null
    }

    private fun stopDotAnimation(finalText: String = "") {
        dotAnimatorJob?.cancel()
        if (finalText.isNotEmpty()) {
            statusText?.text = finalText
        }
    }

    private fun parseMarkdown(text: String): android.text.SpannableStringBuilder {
        val spannable = android.text.SpannableStringBuilder()
        val parts = text.split("**")
        for (i in parts.indices) {
            val start = spannable.length
            spannable.append(parts[i])
            if (i % 2 != 0) {
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    spannable.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return spannable
    }

    /**
     * Fallback duck when [audioManager] is not bound yet (first frames of onShow).
     * Matches [com.tcs.vehicleassistant.hardware.AndroidAudioManager] policy.
     */
    private var fallbackDuckRequest: android.media.AudioFocusRequest? = null
    private val fallbackDuckListener =
        android.media.AudioManager.OnAudioFocusChangeListener { /* session-owned */ }

    private fun requestFallbackDuck() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(fallbackDuckListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                fallbackDuckRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    fallbackDuckListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
            AssistantDebugLog.d("Session", "fallback duck requested")
        } catch (t: Throwable) {
            AssistantDebugLog.w("Session", "fallback duck failed: ${t.message}")
        }
    }

    private fun abandonFallbackDuck() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fallbackDuckRequest?.let { am.abandonAudioFocusRequest(it) }
                fallbackDuckRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(fallbackDuckListener)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        sessionUiVisible = false
        cancelIdleWatch()
        unregisterDismissWatchers()
        dotAnimatorJob?.cancel()
        typewriterJob?.cancel()
        observerScope.cancel()
        composeHost?.destroy()
        composeHost = null
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)
        super.onDestroy()
    }
}


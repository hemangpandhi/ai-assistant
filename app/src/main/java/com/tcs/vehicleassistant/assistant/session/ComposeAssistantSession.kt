package com.tcs.vehicleassistant.assistant.session

import com.tcs.vehicleassistant.llm.LLMManager
import com.tcs.vehicleassistant.assistant.AssistantLlmDebugLabels
import com.tcs.vehicleassistant.WakeWordService
import com.tcs.vehicleassistant.VoiceAnimationView
import com.tcs.vehicleassistant.VehicleManager
import com.tcs.vehicleassistant.R
import com.tcs.vehicleassistant.LocalLLMActivity
import com.tcs.vehicleassistant.LatencyLogger

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
import com.assistant.ui.assistant.audio.AssistantSessionAudioFocus
import com.assistant.ui.assistant.ui.theme.AssistantTheme
import com.assistant.ui.assistant.entry.VirtualAssistantOverlay
import com.assistant.ui.assistant.face.AssistantAdbPreview
import com.assistant.ui.assistant.ui.immersive.AssistantUiLatency
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantDismiss
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantSummon
import com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin
import com.tcs.vehicleassistant.assistant.AssistantIdleTimeout
import com.tcs.vehicleassistant.assistant.AssistantUiMode
import com.tcs.vehicleassistant.assistant.AssistantUiProfile
import com.tcs.vehicleassistant.assistant.VehicleAgentAssistantBackend
import com.tcs.vehicleassistant.assistant.VehicleCabinContextStore
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.ViewModelEvent
import com.tcs.vehicleassistant.core.AssistantConfig
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
 * System voice-interaction session.
 *
 * Default renderer: Compose immersive assistant (design module) via [AssistantUiProfile].
 * Legacy XML voice plates remain available through ADB (`ui=xml:…`).
 */
class ComposeAssistantSession(context: Context) : VoiceInteractionSession(context) {

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
    private val sessionAudioFocus = AssistantSessionAudioFocus(context)
    /** Countdown that closes the overlay after quiet listening. */
    private var idleJob: Job? = null
    /** Collects uiState / events to arm or pause [idleJob]. */
    private var idleWatchJob: Job? = null
    /** Ignore flaky system dismiss signals for a short window after assist/hotword show. */
    private var protectUntilElapsedMs: Long = 0L

    private fun isSummonProtected(): Boolean =
        android.os.SystemClock.elapsedRealtime() < protectUntilElapsedMs

    private fun beginSummonProtection(ms: Long = 2_000L) {
        protectUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + ms
    }

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
            audioManager?.prewarmEar()
            (AssistantRuntime.backend as? VehicleAgentAssistantBackend)?.attachViewModel(
                viewModel,
                audioManager,
                context,
            )
            // Re-assert exclusive focus once the agent is bound (may have missed onShow).
            if (sessionUiVisible) {
                sessionAudioFocus.request()
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
            (AssistantRuntime.backend as? VehicleAgentAssistantBackend)?.attachViewModel(null)
            isBound = false
            agentService = null
            viewModel = null
            audioManager = null
        }
    }

    override fun onHide() {
        super.onHide()
        AssistantDebugLog.d("Session", "onHide")
        sessionUiVisible = false
        cancelIdleWatch()
        baselineResumedActivity = null
        baselineTopPackage = null
        unregisterDismissWatchers()
        // Drop STT before wake-word reclaims the mic.
        notifyImmersiveAssistantDismiss()
        (AssistantRuntime.backend as? VehicleAgentAssistantBackend)?.stopSession()
        audioManager?.stopListening()
        audioManager?.stopSpeaking()
        audioManager?.destroySpeechRecognizer()
        // Release exclusive focus so paused media can resume.
        sessionAudioFocus.abandon()

        observerScope.launch {
            // Let SpeechRecognizer.destroy() finish before Vosk grabs AudioRecord.
            delay(450)
            resumeWakeWordListening()
        }

        unloadJob?.cancel()
        unloadJob = CoroutineScope(Dispatchers.Main).launch {
            delay(600_000)
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

    private fun warmSessionDependencies() {
        observerScope.launch(Dispatchers.IO) {
            runCatching { LocalLLMActivity.loadRuntimePrefs(context.applicationContext) }
            runCatching { VehicleManager.initialize(context.applicationContext) }
            runCatching {
                if (!LLMManager.isReady() && !LocalLLMActivity.isCloudModelActive) {
                    LLMManager.autoInitialize(context.applicationContext)
                }
            }
            withContext(Dispatchers.Main) {
                runCatching { VehicleCabinContextStore.publishFromVehicleManager() }
            }
        }
        // Re-bind if the early bind failed or first pre-draw never ran.
        bindAgentService()
    }

    private fun inflateContentForProfile() {
        val mode = AssistantUiProfile.current()
        currentUiToken = mode.adbToken
        usingComposeUi = mode is AssistantUiMode.Compose
        if (usingComposeUi) {
            inflateComposeLayout()
        } else {
            val xml = mode as AssistantUiMode.Xml
            inflateAndBindLayout(xml.layoutIndex)
        }
    }

    private fun inflateComposeLayout() {
        currentLayoutStyle = -1
        // Tear down any previous Compose host before creating a new one (profile swap).
        composeHost?.destroy()
        composeHost = null

        val host = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Transparent host — ImmersiveBackdrop owns lower-half dim after Compose paints.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // VoiceInteractionSession is not a LifecycleOwner; provide a host that
        // attaches SavedState while still INITIALIZED, then advances to RESUMED.
        val sessionHost = SessionComposeHost().also { it.start() }
        composeHost = sessionHost

        host.setViewTreeLifecycleOwner(sessionHost)
        host.setViewTreeSavedStateRegistryOwner(sessionHost)
        host.setViewTreeViewModelStoreOwner(sessionHost)

        val composeView = ComposeView(context).apply {
            // Keep composition across brief detach (session hide/show) when possible.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                AssistantTheme(darkTheme = true) {
                    VirtualAssistantOverlay(
                        // hide()+finish — some AAOS builds keep the VIS window after hide alone.
                        onDismiss = { dismissForExternalUi("compose-overlay-dismiss") },
                        modifier = Modifier.fillMaxSize(),
                        awaitHotword = false,
                        // Wait for onShow → notifyImmersiveAssistantSummon(origin).
                        autoPresent = false,
                        // Agent owns STT via IAudioManager (same path as XML).
                        enableLiveSpeech = false,
                        // Agent owns TTS via orchestrator audioManager.
                        enableTts = false,
                    )
                }
            }
        }
        host.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlayView = host
        setContentView(overlayView)
    }

    private fun inflateAndBindLayout(layoutStyle: Int) {
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
                viewModel?.processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.ProcessQuery(query))
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
     */
    private fun startIdleWatch() {
        AssistantIdleTimeout.install(context)
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
                            is ViewModelEvent.StartListening -> armIdleTimer("start-listening")
                            else -> Unit
                        }
                    }
                }
            }
            // Begin counting only after the mic is actually listening (see onUiStateForIdle).
            // Arming at show raced the 1.4s STT delay and closed the overlay mid-warmup.
            if (viewModel?.uiState?.value is AssistantUiState.Listening) {
                armIdleTimer("watch-start-listening")
            } else {
                pauseIdleTimer()
                AssistantDebugLog.d("Session", "idle wait until Listening")
            }
        }
    }

    private fun cancelIdleWatch() {
        idleJob?.cancel()
        idleJob = null
        idleWatchJob?.cancel()
        idleWatchJob = null
    }

    private fun onUiStateForIdle(state: AssistantUiState) {
        when (state) {
            is AssistantUiState.Thinking,
            is AssistantUiState.Streaming,
            is AssistantUiState.Speaking,
            -> pauseIdleTimer()
            // STT empty/timeout can bounce Error → Listening while quietly re-arming
            // inside the ~5s listen window. Do not reset the countdown on that churn,
            // or the session outlasts the "5s max" no-speech budget.
            is AssistantUiState.Error -> Unit
            is AssistantUiState.Idle,
            is AssistantUiState.Listening,
            -> {
                if (isModelWarmingUp()) {
                    pauseIdleTimer()
                } else if (idleJob?.isActive != true) {
                    armIdleTimer("ui:${state::class.simpleName}")
                }
            }
        }
    }

    private fun isModelWarmingUp(): Boolean {
        if (LocalLLMActivity.isCloudModelActive) return false
        return LLMManager.isInitializing || !LLMManager.isReady()
    }

    /** Reset the idle countdown after real user / pipeline activity. */
    private fun noteUserActivity() {
        if (!sessionUiVisible) return
        if (viewModel?.isProcessing() == true) {
            pauseIdleTimer()
            return
        }
        armIdleTimer("user-activity")
    }

    private fun pauseIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun armIdleTimer(reason: String) {
        idleJob?.cancel()
        if (!sessionUiVisible) return
        if (AssistantAdbPreview.isHolding()) {
            AssistantDebugLog.d("Session", "idle timer paused — ADB preview holding ($reason)")
            idleJob = null
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
            dismissForIdleTimeout()
        }
    }

    private fun dismissForIdleTimeout() {
        // ADB mood / face-cue preview holds the stage until cleared or dismissed.
        if (AssistantAdbPreview.isHolding()) {
            AssistantDebugLog.d("Session", "idle-timeout skipped — ADB preview holding")
            pauseIdleTimer()
            return
        }
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
                            context.applicationContext.startActivity(event.intent)
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "startActivity failed for intent: ${event.intent}", e)
                        }
                        // Match XML path: dismissForExternalUi owns hide()+finish().
                        // Calling hide() first cleared sessionUiVisible and skipped finish().
                        dismissForExternalUi("compose-launch-intent")
                    }
                    is ViewModelEvent.FinishSession -> {
                        // Match XML FinishSession → finish(); play Compose exit first.
                        AssistantDebugLog.d("Session", "FinishSession — close overlay + session")
                        notifyImmersiveAssistantDismiss()
                        observerScope.launch {
                            delay(320L)
                            dismissForExternalUi("compose-finish-session")
                        }
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

        viewModel?.processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.ResetTurn)

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
                        voiceAnimation?.state = VoiceAnimationView.State.LISTENING
                        if (state.partialText.isNotEmpty()) {
                            responseText?.text = "\"${state.partialText}\""
                            responseText?.gravity = android.view.Gravity.CENTER
                        }
                    }
                    is AssistantUiState.Thinking -> {
                        resetDisplayState()
                        statusText?.visibility = View.VISIBLE
                        startDotAnimation("")
                        voiceAnimation?.state = VoiceAnimationView.State.THINKING
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
                            this@ComposeAssistantSession.startVoiceActivity(event.intent)
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "startVoiceActivity failed", e)
                            try {
                                event.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.applicationContext.startActivity(event.intent)
                            } catch (e2: Exception) {
                                android.util.Log.e("AssistantSession", "Fallback startActivity failed", e2)
                            }
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
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        AssistantDebugLog.d("Session", "onShow args: $args, showFlags: $showFlags")
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

        // Take exclusive focus immediately so media pauses for the assistant.
        sessionAudioFocus.request()

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

        // Pause (not stop): keep the :wakeword process + Vosk model warm for the next turn.
        pauseWakeWordListening()

        val initialQuery = args?.getString("INITIAL_QUERY")
        val directSpeech = args?.getString("DIRECT_SPEECH")
        if (initialQuery != null) {
            observerScope.launch {
                delay(200) // Small delay to allow Compose UI to appear and bind
                viewModel?.processIntent(com.tcs.vehicleassistant.controller.AssistantIntent.ProcessQuery(initialQuery))
            }
        }

        if (usingComposeUi) {
            // Wake-word mic released; open agent STT after summon.
            val origin = ImmersiveSummonOrigin.fromBundleToken(
                args?.getString(ImmersiveSummonOrigin.BUNDLE_KEY),
            )
            // post + delayed retry: composition may register the summon bridge a frame late.
            overlayView.post {
                val backend = AssistantRuntime.backend as? VehicleAgentAssistantBackend
                backend?.bindContext(context)
                notifyImmersiveAssistantSummon(origin)
                if (initialQuery == null && directSpeech == null) {
                    backend?.requestListen()
                }
            }
            overlayView.postDelayed(
                { notifyImmersiveAssistantSummon(origin) },
                80L,
            )
            observerScope.launch(Dispatchers.IO) {
                runCatching {
                    if (!LLMManager.isReady() && !LocalLLMActivity.isCloudModelActive) {
                        LLMManager.autoInitialize(context.applicationContext)
                    }
                }
            }
            if ((showFlags and SHOW_WITH_ASSIST) != 0) {
                AssistantDebugLog.d("Session", "SHOW_WITH_ASSIST — agent STT will listen")
            }
            return
        }

        statusText?.visibility = View.VISIBLE
        stopDotAnimation("Hi, how can I help you?")
        responseText?.text = ""
        etInput?.setText("")
        voiceAnimation?.state = VoiceAnimationView.State.IDLE

        if (!LLMManager.isReady() || LLMManager.isInitializing) {
            statusText?.text = "Initializing Model..."
            btnOpenApp?.visibility = View.GONE
            inputControls?.visibility = View.GONE
            btnMic?.isEnabled = false
            pauseIdleTimer()

            CoroutineScope(Dispatchers.Main).launch {
                if (!LLMManager.isReady() && !LLMManager.isInitializing) {
                    withContext(Dispatchers.IO) {
                        runCatching { LLMManager.autoInitialize(context.applicationContext) }
                    }
                }
                // Wait until LLMManager is fully ready and not prewarming.
                withContext(Dispatchers.IO) {
                    val waitStart = System.currentTimeMillis()
                    while (!LLMManager.isReady() || LLMManager.isInitializing) {
                        if (System.currentTimeMillis() - waitStart > 30_000L) {
                            break // Prevent infinite UI hang
                        }
                        delay(500)
                    }
                }
                
                if (!LLMManager.isReady()) {
                    statusText?.text = "Initialization failed. Please try again."
                    btnOpenApp?.visibility = View.VISIBLE
                    inputControls?.visibility = View.VISIBLE
                    btnSend?.isEnabled = false
                    btnMic?.isEnabled = false
                    return@launch
                }
                
                statusText?.text = "Hi, how can I help you?"
                inputControls?.visibility = View.VISIBLE
                btnSend?.isEnabled = true
                btnMic?.isEnabled = true
                armIdleTimer("model-ready")
                if (initialQuery == null && (showFlags and SHOW_WITH_ASSIST) != 0) {
                    delay(500) // Wait for WakeWordService to release the mic
                    btnMic?.performClick()
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

            if (initialQuery == null && (showFlags and SHOW_WITH_ASSIST) != 0) {
                observerScope.launch {
                    delay(500) // Wait for WakeWordService to release the mic
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
        if (typewriterJob == null || typewriterJob?.isActive != true) {
            typewriterJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive && currentDisplayLength < targetDisplayMessage.length) {
                    val timeSinceTts = System.currentTimeMillis() - (viewModel?.lastTtsUpdateTime ?: 0L)
                    val isTtsActive = (viewModel?.lastTtsUpdateTime ?: 0L) > 0L && timeSinceTts < 2000
                    if (isTtsActive && currentDisplayLength > (viewModel?.ttsSpokenLength ?: 0) + 5) {
                        delay(16)
                        continue
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
        dotAnimatorJob = CoroutineScope(Dispatchers.Main).launch {
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
        resumeWakeWordListening()
        super.onDestroy()
    }

    /**
     * Re-arms wake-word listening after the session ends.
     *
     * Skipped while [AssistantConfig.isWakeWordDisabledForTest] so Vosk does not
     * reclaim the mic during ear STT bring-up.
     */
    private fun resumeWakeWordListening() {
        if (AssistantConfig.isWakeWordDisabledForTest(context)) {
            AssistantDebugLog.d("Session", "WAKE_WORD_DISABLED_FOR_TEST — skip wake RESTART")
            return
        }
        sendWakeWordCommand(AssistantConfig.WakeWordAction.RESTART)
    }

    private fun pauseWakeWordListening() {
        if (AssistantConfig.isWakeWordDisabledForTest(context)) return
        sendWakeWordCommand(AssistantConfig.WakeWordAction.PAUSE)
    }

    private fun sendWakeWordCommand(action: String) {
        try {
            context.startService(Intent(context, WakeWordService::class.java).setAction(action))
        } catch (e: Exception) {
            AssistantDebugLog.e("Session", "Failed to send '$action' to WakeWordService: ${e.message}")
        }
    }
}


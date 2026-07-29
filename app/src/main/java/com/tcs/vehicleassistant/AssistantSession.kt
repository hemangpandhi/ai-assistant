package com.tcs.vehicleassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.assistant.ui.assistant.api.AssistantDebugLog
import com.assistant.ui.assistant.api.AssistantBackend
import com.assistant.ui.assistant.api.AssistantRuntime
import com.assistant.ui.assistant.ui.theme.AssistantTheme
import com.assistant.ui.assistant.entry.VirtualAssistantOverlay
import com.assistant.ui.assistant.ui.immersive.AssistantUiLatency
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantDismiss
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantSummon
import com.assistant.ui.assistant.ui.immersive.ImmersiveSummonOrigin
import com.tcs.vehicleassistant.assistant.AssistantUiMode
import com.tcs.vehicleassistant.assistant.AssistantUiProfile
import com.tcs.vehicleassistant.assistant.VehicleCabinContextStore
import com.tcs.vehicleassistant.assistant.session.AssistantSessionDismissController
import com.tcs.vehicleassistant.assistant.session.AssistantSessionIdleController
import com.tcs.vehicleassistant.assistant.session.SessionComposeHost
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.ViewModelEvent
import com.tcs.vehicleassistant.hardware.AssistantAudioFocusDucker
import com.tcs.vehicleassistant.hardware.SessionAudioPort
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

    private var audioManager: SessionAudioPort? = null
    private var viewModel: AssistantViewModel? = null
    private var agentService: VehicleAgentService? = null
    private var isBound = false
    private var usingComposeUi = true
    private var currentUiToken: String = ""
    private var currentLayoutStyle = -1
    private var composeHost: SessionComposeHost? = null
    /** True between [onShow] and [onHide] — used to dismiss when another system-bar UI opens. */
    private var sessionUiVisible = false

    private var dotAnimatorJob: Job? = null
    private var typewriterJob: Job? = null
    private var targetDisplayMessage = ""
    private var currentDisplayLength = 0
    private val typingSpeedMs: Long = 15L
    private var unloadJob: Job? = null
    /** Opens agent STT after wake-word AudioRecord is released. */
    private var micHandoffJob: Job? = null
    /** Delayed wake-word restart after hide — must cancel on re-show. */
    private var wakeRestartJob: Job? = null

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val idleController = AssistantSessionIdleController(
        context = context,
        scope = observerScope,
        isVisible = { sessionUiVisible },
        isBusy = { viewModel?.isProcessing() == true },
        currentUiState = { viewModel?.uiState?.value },
        viewModelProvider = { viewModel },
        onTimeout = ::dismissForIdleTimeout,
    )
    private val dismissController = AssistantSessionDismissController(
        context = context,
        scope = observerScope,
        overlayViewProvider = {
            if (::overlayView.isInitialized) overlayView else null
        },
        isVisible = { sessionUiVisible },
        onDismiss = ::dismissForExternalUi,
    )
    private val fallbackAudioFocusDucker = AssistantAudioFocusDucker(context)

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
                idleController.start()
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
        AssistantDebugLog.d("Session", "onHide")
        sessionUiVisible = false
        idleController.stop()
        micHandoffJob?.cancel()
        micHandoffJob = null
        dismissController.stop()
        teardownAssistantAudio(reason = "onHide")

        wakeRestartJob?.cancel()
        wakeRestartJob = observerScope.launch {
            // Let SpeechRecognizer cancel/settle before Vosk grabs AudioRecord.
            delay(450)
            val restartIntent = Intent(context, WakeWordService::class.java)
            restartIntent.action = "ACTION_RESTART_LISTENING"
            context.startService(restartIntent)
        }

        unloadJob?.cancel()
        // Unload sooner under idle — keep warm only for recent interaction (~90s).
        unloadJob = observerScope.launch {
            delay(90_000)
            LLMManager.unload()
        }
    }

    /** Shared hide/destroy audio teardown — idempotent via backend.stopSession. */
    private fun teardownAssistantAudio(reason: String) {
        AssistantDebugLog.d("Session", "teardownAudio $reason")
        notifyImmersiveAssistantDismiss()
        AssistantRuntime.backend?.stopSession()
        audioManager?.stopSpeaking()
        com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.clearSessionArm()
        audioManager?.abandonAssistantDuck()
        fallbackAudioFocusDucker.abandon()
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
                if (!LLMManager.isReady() && !isCloudRoutingActive()) {
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
            // Soft scrim so the window paints before Compose finishes (matches AssistantTokens.Scrim).
            setBackgroundColor(0x52101014.toInt())
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
                        onDismiss = { hide() },
                        modifier = Modifier.fillMaxSize(),
                        awaitHotword = false,
                        // Wait for onShow → notifyImmersiveAssistantSummon(origin).
                        autoPresent = false,
                        // Agent owns STT via SessionAudioPort (same path as XML).
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
                idleController.noteActivity()
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
            idleController.noteActivity()
            LatencyLogger.reset()
            LatencyLogger.log("AssistantSession", "Voice Button Clicked")
            audioManager?.stopSpeaking()
            btnMic?.isEnabled = false
            LatencyLogger.log("AssistantSession", "Speech Recognizer startListening() called")
            try {
                // Prefer backend schedule so XML mic shares the single-owner arm path.
                val mic = AssistantRuntime.backend?.asMicController()
                if (mic != null) {
                    mic.requestListen()
                } else {
                    audioManager?.startListening()
                }
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
                if (!s.isNullOrEmpty()) idleController.noteActivity()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setContentView(overlayView)
    }

    private fun isCloudRoutingActive(): Boolean {
        return runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags>()
                .isCloudActive
        }.getOrElse { LocalLLMActivity.isCloudModelActive }
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
                            context.applicationContext.startActivity(event.intent)
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "startActivity failed for intent: ${event.intent}", e)
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
                        voiceAnimation?.state = VoiceAnimationView.State.LISTENING
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
        // Cancel stale hide→wake restart so Vosk cannot reclaim the mic mid-STT.
        wakeRestartJob?.cancel()
        wakeRestartJob = null
        dismissController.start()
        idleController.start()

        // Duck music immediately — do not wait for STT (~1.4s) or service bind.
        audioManager?.requestAssistantDuck() ?: fallbackAudioFocusDucker.request()

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

        if (usingComposeUi) {
            // Stop wake-word AudioRecord fully, then open agent STT.
            val origin = ImmersiveSummonOrigin.fromBundleToken(
                args?.getString(ImmersiveSummonOrigin.BUNDLE_KEY),
            )
            // Summon UI immediately. STT is pre-armed on hotword/icon; only fall back here.
            overlayView.post {
                notifyImmersiveAssistantSummon(origin)
            }
            overlayView.postDelayed(
                { notifyImmersiveAssistantSummon(origin) },
                80L,
            )
            micHandoffJob?.cancel()
            micHandoffJob = observerScope.launch {
                if (com.tcs.vehicleassistant.hardware.MicCaptureCoordinator.isCaptureLive(audioManager) ||
                    audioManager?.isActivelyListening() == true
                ) {
                    AssistantDebugLog.d("Session", "mic already live — overlay attaches only")
                    AssistantRuntime.backend?.asMicController()?.requestListen()
                    return@launch
                }
                val released = WakeWordService.awaitMicReleased(timeoutMs = 900L)
                AssistantDebugLog.d(
                    "Session",
                    "mic handoff fallback released=$released holding=${WakeWordService.isHoldingMic}",
                )
                delay(25)
                if (!sessionUiVisible) return@launch
                AssistantRuntime.backend?.asMicController()?.requestListen()
            }
            observerScope.launch(Dispatchers.IO) {
                runCatching {
                    if (!LLMManager.isReady() && !isCloudRoutingActive()) {
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

        if (!LLMManager.isReady() || LLMManager.isInitializing || LLMManager.isPrewarming) {
            statusText?.text = "Initializing Model..."
            btnOpenApp?.visibility = View.GONE
            inputControls?.visibility = View.GONE
            btnMic?.isEnabled = false
            idleController.pause()

            observerScope.launch {
                if (!LLMManager.isReady() && !LLMManager.isInitializing && !LLMManager.isPrewarming) {
                    withContext(Dispatchers.IO) {
                        runCatching { LLMManager.autoInitialize(context.applicationContext) }
                    }
                }
                // Wait until LLMManager is fully ready and not prewarming.
                withContext(Dispatchers.IO) {
                    while (!LLMManager.isReady() || LLMManager.isInitializing || LLMManager.isPrewarming) {
                        delay(500)
                    }
                }
                statusText?.text = "Hi, how can I help you?"
                inputControls?.visibility = View.VISIBLE
                btnSend?.isEnabled = true
                btnMic?.isEnabled = true
                idleController.arm("model-ready")
                if (showFlags and SHOW_WITH_ASSIST != 0) {
                    delay(250) // Wait for WakeWordService to release the mic
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
            idleController.arm("xml-ready")

            if (showFlags and SHOW_WITH_ASSIST != 0) {
                observerScope.launch {
                    delay(250)
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
            typewriterJob = observerScope.launch {
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
        dotAnimatorJob = observerScope.launch {
            var dotCount = 0
            while (isActive) {
                statusText?.text = "$baseText${".".repeat(dotCount)}"
                dotCount = (dotCount + 1) % 4
                delay(400)
            }
        }
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
        idleController.destroy()
        micHandoffJob?.cancel()
        micHandoffJob = null
        dismissController.destroy()
        dotAnimatorJob?.cancel()
        typewriterJob?.cancel()
        // Mirror onHide audio teardown so Vosk cannot race an open SpeechRecognizer.
        teardownAssistantAudio(reason = "onDestroy")
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


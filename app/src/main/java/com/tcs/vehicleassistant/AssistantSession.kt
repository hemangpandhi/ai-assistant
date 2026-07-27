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
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantDismiss
import com.assistant.ui.assistant.ui.immersive.notifyImmersiveAssistantHotword
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
            observerScope.launch {
                delay(80)
                if (sessionUiVisible && ::overlayView.isInitialized &&
                    !overlayView.hasWindowFocus()
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
            // Home / recent / system bar often fires this when leaving the assistant.
            dismissForExternalUi("close-system-dialogs:$reason")
        }
    }

    private fun dismissForExternalUi(reason: String) {
        if (!sessionUiVisible) return
        AssistantDebugLog.d("Session", "dismiss overlay ($reason)")
        sessionUiVisible = false
        runCatching { hide() }
        // Some AAOS builds keep the session window until finish().
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
            if (usingComposeUi) {
                startObservingComposeAgentEvents()
            } else {
                startObservingViewModel()
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
        baselineResumedActivity = null
        baselineTopPackage = null
        unregisterDismissWatchers()
        // Drop STT before wake-word reclaims the mic.
        notifyImmersiveAssistantDismiss()
        AssistantRuntime.backend?.stopSession()
        audioManager?.stopListening()
        audioManager?.stopSpeaking()
        audioManager?.destroySpeechRecognizer()

        observerScope.launch {
            // Let SpeechRecognizer.destroy() finish before Vosk grabs AudioRecord.
            delay(450)
            val restartIntent = Intent(context, WakeWordService::class.java)
            restartIntent.action = "ACTION_RESTART_LISTENING"
            context.startService(restartIntent)
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
        AssistantUiProfile.install(context)
        inflateContentForProfile()

        // Bind the agent immediately so startMic isn't racing a deferred post{}.
        val intent = Intent(context, VehicleAgentService::class.java)
        runCatching {
            context.startForegroundService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Heavy car / prefs / LLM work runs after the first frame.
        overlayView.post {
            warmSessionDependencies()
        }
        return overlayView
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
        // Re-bind if the early bind in onCreateContentView failed.
        if (!isBound) {
            val intent = Intent(context, VehicleAgentService::class.java)
            runCatching {
                context.startForegroundService(intent)
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
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
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AssistantTheme(darkTheme = true) {
                    VirtualAssistantOverlay(
                        onDismiss = { hide() },
                        modifier = Modifier.fillMaxSize(),
                        awaitHotword = false,
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

        setContentView(overlayView)
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
                            
                            // Optionally hide the assistant UI so the newly launched app is visible
                            this@AssistantSession.hide()
                        } catch (e: Exception) {
                            android.util.Log.e("AssistantSession", "startActivity failed for intent: ${event.intent}", e)
                        }
                        // Voice session windows stay on top of newly launched apps unless
                        // we explicitly tear the session down.
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
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantDebugLog.d("Session", "onShow flags=$showFlags compose=$usingComposeUi")
        sessionUiVisible = true
        baselineResumedActivity = null
        baselineTopPackage = null
        registerDismissWatchers()

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
            overlayView.post {
                notifyImmersiveAssistantHotword()
                AssistantRuntime.backend?.asMicController()?.requestListen()
            }
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

        if (!LLMManager.isReady() || LLMManager.isInitializing || LLMManager.isPrewarming) {
            statusText?.text = "Initializing Model..."
            btnOpenApp?.visibility = View.GONE
            inputControls?.visibility = View.GONE
            btnMic?.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
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
                if (showFlags and SHOW_WITH_ASSIST != 0) {
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

            if (showFlags and SHOW_WITH_ASSIST != 0) {
                CoroutineScope(Dispatchers.Main).launch {
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
                            dismissForExternalUi("top-pkg $baselineTopPackage → $confirmed")
                            break
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


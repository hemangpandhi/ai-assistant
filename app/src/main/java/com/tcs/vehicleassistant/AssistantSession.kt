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
import com.test.design.assistant.api.AssistantRuntime
import com.test.design.presentation.assistant.AssistantTheme
import com.test.design.presentation.assistant.VirtualAssistantOverlay
import com.tcs.vehicleassistant.assistant.AssistantUiMode
import com.tcs.vehicleassistant.assistant.AssistantUiProfile
import com.tcs.vehicleassistant.assistant.VehicleAgentAssistantBackend
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

    private var dotAnimatorJob: Job? = null
    private var typewriterJob: Job? = null
    private var targetDisplayMessage = ""
    private var currentDisplayLength = 0
    private val typingSpeedMs: Long = 15L
    private var unloadJob: Job? = null

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VehicleAgentService.LocalBinder
            agentService = binder.getService()
            isBound = true
            viewModel = agentService?.viewModel
            audioManager = agentService?.audioManager
            (AssistantRuntime.backend as? VehicleAgentAssistantBackend)?.attachViewModel(viewModel)
            if (!usingComposeUi) {
                startObservingViewModel()
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
        isSessionVisible = false
        audioManager?.stopListening()
        audioManager?.stopSpeaking()
        viewModel?.resetState()

        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)

        unloadJob?.cancel()
        
        // Smart Memory Management: Only unload the heavy LLM from RAM if the car is parked.
        // If the car is driving (or in any other gear), we keep it loaded so it's instantly available.
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600 || android.os.Build.DEVICE.contains("tangorpro", ignoreCase = true)
        if (!isTablet && VehicleManager.getGearSelection() == "Park") {
             LLMManager.unload()
        }
    }

    override fun onCreateContentView(): View {
        LocalLLMActivity.loadRuntimePrefs(context.applicationContext)
        VehicleManager.initialize(context.applicationContext)
        VehicleCabinContextStore.publishFromVehicleManager()
        AssistantUiProfile.install(context)

        val intent = Intent(context, VehicleAgentService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        inflateContentForProfile()
        return overlayView
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
        if (modelInfoTag != null) {
            if (LocalLLMActivity.isCloudModelActive) {
                modelInfoTag.text = "${LocalLLMActivity.currentCloudModelName} ☁️"
            } else {
                val modelName = java.io.File(LLMManager.currentModelPath).nameWithoutExtension
                modelInfoTag.text = if (modelName.isNotEmpty()) modelName else "Gemma 4 E2B"
            }
        }

        val activeBackendTag: TextView? = overlayView.findViewById(R.id.activeBackendTag)
        if (activeBackendTag != null) {
            if (LocalLLMActivity.isCloudModelActive) {
                activeBackendTag.text = "Backend: Cloud"
            } else {
                activeBackendTag.text = "Backend: ${LLMManager.activeBackendString}"
            }
        }

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

        val rootOverlay = overlayView.findViewById<View>(R.id.rootOverlay)
        rootOverlay?.setOnClickListener {
            teardownSession()
        }

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

    private var isSessionVisible = false

    private fun teardownSession() {
        android.util.Log.d("AssistantSession", "Tearing down complete session")
        audioManager?.stopListening()
        audioManager?.stopSpeaking()
        viewModel?.resetState()
        val restartIntent = Intent(context, WakeWordService::class.java)
        restartIntent.action = "ACTION_RESTART_LISTENING"
        context.startService(restartIntent)
        hide()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SESSION LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        if (isSessionVisible) {
            android.util.Log.d("AssistantSession", "Assistant re-triggered while active. Tearing down complete session.")
            teardownSession()
            return
        }
        isSessionVisible = true

        // Force window to occupy the entire screen on Automotive OS to prevent UI chopping
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
                
                // Automatically start listening on session open
                CoroutineScope(Dispatchers.Main).launch {
                    delay(300) // Wait for WakeWordService to release the mic
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

            // Automatically start listening on session open
            CoroutineScope(Dispatchers.Main).launch {
                delay(300) // Wait for WakeWordService to release the mic
                btnMic.performClick()
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
        dotAnimatorJob = CoroutineScope(Dispatchers.Main).launch {
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


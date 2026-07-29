package com.tcs.vehicleassistant
import kotlinx.coroutines.*

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.tcs.vehicleassistant.controller.AssistantUiState
import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.controller.ViewModelEvent
import com.tcs.vehicleassistant.hardware.AndroidAudioManager
import com.tcs.vehicleassistant.hardware.IAudioManager


class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    // ── View references ─────────────────────────────────────────────────────
    private lateinit var overlayView: View
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: ImageButton
    private lateinit var btnOpenApp: Button
    private lateinit var inputControls: View
    private lateinit var voiceAnimation: VoiceAnimationView
    private var svResponse: android.widget.ScrollView? = null

    // ── Hardware Abstraction ────────────────────────────────────────────────
    private var audioManager: IAudioManager? = null

    // ── ViewModel (all business logic lives here) ───────────────────────────
    private var viewModel: AssistantViewModel? = null
    
    // ── Service Connection ──────────────────────────────────────────────────
    private var agentService: com.tcs.vehicleassistant.service.VehicleAgentService? = null
    private var isBound = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: android.content.ComponentName, service: android.os.IBinder) {
            val binder = service as com.tcs.vehicleassistant.service.VehicleAgentService.LocalBinder
            agentService = binder.getService()
            isBound = true
            
            viewModel = agentService?.viewModel
            audioManager = agentService?.audioManager
            
            startObservingViewModel()
        }

        override fun onServiceDisconnected(arg0: android.content.ComponentName) {
            isBound = false
            agentService = null
            viewModel = null
            audioManager = null
        }
    }

    // ── UI Animation State ──────────────────────────────────────────────────
    private var dotAnimatorJob: Job? = null
    private var typewriterJob: Job? = null
    private var targetDisplayMessage = ""
    private var currentDisplayLength = 0
    private val typingSpeedMs: Long = 15L
    private var currentHighlightStart = -1
    private var currentHighlightEnd = -1

    // ── Lifecycle ───────────────────────────────────────────────────────────
    private var currentLayoutStyle = -1
    private var unloadJob: Job? = null

    // ── Observation scope ───────────────────────────────────────────────────
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ═══════════════════════════════════════════════════════════════════════
    // VIEW LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

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
        
        // Bind to background agent service
        val intent = Intent(context, com.tcs.vehicleassistant.service.VehicleAgentService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        inflateAndBindLayout()

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

        val layoutRes = when (layoutStyle) {
            0 -> R.layout.assistant_overlay // Polestar Wide
            1 -> R.layout.assistant_overlay_pill // Center Pill
            2 -> R.layout.assistant_overlay_side // Left Side Panel
            3 -> R.layout.assistant_overlay_top // Top Banner
            4 -> R.layout.assistant_overlay_immersive // Full-Screen Immersive
            5 -> R.layout.assistant_overlay_hud // Holographic Cyberpunk HUD
            6 -> R.layout.assistant_overlay_beveled // Beveled Glass Island
            7 -> R.layout.assistant_overlay_cinematic // Cinematic Letterbox
            else -> R.layout.assistant_overlay
        }
        overlayView = layoutInflater.inflate(layoutRes, null)
        statusText = overlayView.findViewById(R.id.assistantResponseText) // Routed to main text
        responseText = overlayView.findViewById(R.id.assistantResponseText)
        etInput = overlayView.findViewById(R.id.etInput)
        btnSend = overlayView.findViewById(R.id.btnSend)
        btnMic = overlayView.findViewById(R.id.btnMic)
        btnOpenApp = overlayView.findViewById(R.id.btnOpenApp)
        svResponse = overlayView.findViewById(R.id.svResponse)
        inputControls = overlayView.findViewById(R.id.inputControlsContainer)
        voiceAnimation = overlayView.findViewById(R.id.voiceAnimation)

        val modelInfoTag: android.widget.TextView? = overlayView.findViewById(R.id.modelInfoTag)
        if (modelInfoTag != null) {
            if (LocalLLMActivity.isCloudModelActive) {
                modelInfoTag.text = "${LocalLLMActivity.currentCloudModelName} ☁️"
            } else {
                val modelName = java.io.File(LLMManager.currentModelPath).nameWithoutExtension
                modelInfoTag.text = if (modelName.isNotEmpty()) modelName else "Gemma 4 E2B"
            }
        }

        val activeBackendTag: android.widget.TextView? = overlayView.findViewById(R.id.activeBackendTag)
        if (activeBackendTag != null) {
            if (LocalLLMActivity.isCloudModelActive) {
                activeBackendTag.text = "Backend: Cloud"
            } else {
                activeBackendTag.text = "Backend: ${LLMManager.activeBackendString}"
            }
        }

        // Global Adaptive Gravity Logic
        responseText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val len = s?.length ?: 0
                if (len < 50) {
                    responseText.gravity = android.view.Gravity.CENTER
                } else {
                    responseText.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                }
            }
        })

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val blurView = overlayView.findViewById<android.view.View>(R.id.blurBackgroundView)
            blurView?.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(25f, 25f, android.graphics.Shader.TileMode.CLAMP)
            )
        }

        val rootOverlay = overlayView.findViewById<View>(R.id.rootOverlay)
        rootOverlay?.setOnClickListener {
            teardownSession()
        }

        btnOpenApp.setOnClickListener {
            val intent = Intent(context, LocalLLMActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            hide()
        }

        btnSend.setOnClickListener {
            val query = etInput.text.toString()
            if (query.isNotBlank()) {
                audioManager?.stopSpeaking()
                resetDisplayState()
                viewModel?.handleQuery(query)
                etInput.setText("")
            }
        }

        btnMic.setOnClickListener {
            if (viewModel?.isProcessing() == true) {
                android.util.Log.w("AssistantSession", "Ignoring mic trigger because query is still being processed.")
                return@setOnClickListener
            }
            LatencyLogger.reset()
            LatencyLogger.log("AssistantSession", "Voice Button Clicked")
            audioManager?.stopSpeaking()
            btnMic.isEnabled = false
            LatencyLogger.log("AssistantSession", "Speech Recognizer startListening() called")

            try {
                statusText.visibility = View.VISIBLE
                statusText.text = "Listening..."
                voiceAnimation.state = VoiceAnimationView.State.LISTENING
                audioManager?.startListening()
            } catch (e: Exception) {
                LatencyLogger.log("AssistantSession", "Error starting speech recognizer: ${e.message}")
                stopDotAnimation("Error starting microphone.")
                statusText.visibility = View.VISIBLE
                voiceAnimation.state = VoiceAnimationView.State.IDLE
            }
            btnMic.isEnabled = true
        }

        // Update the active content view window with the newly inflated view
        setContentView(overlayView)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VIEWMODEL OBSERVATION (the core of the MVVM pattern)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startObservingViewModel() {
        if (viewModel == null) return
        
        viewModel?.resetUiState()

        // Observe UI state changes
        observerScope.launch {
            viewModel?.uiState?.collect { state ->
                when (state) {
                    is AssistantUiState.Idle -> {
                        voiceAnimation.state = VoiceAnimationView.State.IDLE
                        stopDotAnimation()
                    }
                    is AssistantUiState.Listening -> {
                        statusText.visibility = View.VISIBLE
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
                        statusText.visibility = View.VISIBLE
                        startDotAnimation("")
                        voiceAnimation.state = VoiceAnimationView.State.THINKING
                        if (!state.userQuery.isNullOrBlank()) {
                            responseText.text = "\"${state.userQuery}\""
                            responseText.gravity = android.view.Gravity.CENTER
                        }
                    }
                    is AssistantUiState.Streaming -> {
                        if (statusText.visibility == View.VISIBLE) {
                            stopDotAnimation()
                            voiceAnimation.state = VoiceAnimationView.State.SPEAKING
                        }
                        targetDisplayMessage = state.displayText
                        startTypewriterIfNeeded()
                    }
                    is AssistantUiState.Speaking -> {
                        voiceAnimation.state = VoiceAnimationView.State.SPEAKING
                        stopDotAnimation()
                        targetDisplayMessage = state.finalMessage
                        startTypewriterIfNeeded()
                    }
                    is AssistantUiState.Error -> {
                        voiceAnimation.state = VoiceAnimationView.State.IDLE
                        stopDotAnimation()
                        responseText.text = state.errorMessage
                    }
                }
            }
        }

        // Observe events (tool calls, confirmation dialogs)
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
                    is ViewModelEvent.FinishSession -> {
                        finish()
                    }
                    is ViewModelEvent.StartListening -> {
                        btnMic.performClick()
                    }
                    is ViewModelEvent.ShowToast -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    }
                    is ViewModelEvent.SetInputEnabled -> {
                        btnSend.isEnabled = event.enabled
                        btnMic.isEnabled = event.enabled
                    }
                    is ViewModelEvent.SetInputText -> {
                        etInput.setText(event.text)
                    }
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
            android.util.Log.d("AssistantSession", "Assistant re-triggered while active. Keeping active session visible.")
            return
        }
        isSessionVisible = true

        // Force window to occupy the entire screen on Automotive OS to prevent UI chopping
        window?.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        unloadJob?.cancel()

        // Re-inflate if layout setting changed
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("ui_layout_pref", 0) != currentLayoutStyle) {
            inflateAndBindLayout()
        }

        statusText.visibility = View.VISIBLE
        stopDotAnimation("Hi, how can I help you?")
        responseText.text = ""
        etInput.setText("")
        voiceAnimation.state = VoiceAnimationView.State.IDLE

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
            // DO NOT reset the conversation here. Resetting invalidates the KV cache
            // and forces the LLM to re-process the massive System Prompt, causing a 2-3s delay.
            statusText.visibility = View.VISIBLE
            statusText.text = "Hi, how can I help you?"
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.VISIBLE
            btnSend.isEnabled = true
            btnMic.isEnabled = true

            // Automatically start listening on session open
            CoroutineScope(Dispatchers.Main).launch {
                delay(300) // Wait for WakeWordService to release the mic
                btnMic.performClick()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI HELPERS (pure rendering, no business logic)
    // ═══════════════════════════════════════════════════════════════════════

    private fun resetDisplayState() {
        responseText.text = ""
        currentHighlightStart = -1
        currentHighlightEnd = -1
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
                    val currentSubstring = targetDisplayMessage.substring(0, currentDisplayLength)
                    responseText.text = currentSubstring

                    // Auto-scroll to bottom efficiently
                    if (currentDisplayLength % 5 == 0) {
                        svResponse?.post {
                            svResponse?.fullScroll(View.FOCUS_DOWN)
                        }
                    }

                    delay(typingSpeedMs)
                }
                // Apply Markdown formatting only once after typewriter finishes
                if (targetDisplayMessage.isNotEmpty()) {
                    responseText.text = parseMarkdown(targetDisplayMessage)
                }
            }
        }
    }

    private fun startDotAnimation(baseText: String) {
        dotAnimatorJob?.cancel()
        if (baseText.isEmpty()) {
            statusText.text = ""
            return
        }
        dotAnimatorJob = CoroutineScope(Dispatchers.Main).launch {
            var dotCount = 0
            while (isActive) {
                val dots = ".".repeat(dotCount)
                statusText.text = "$baseText$dots"
                dotCount = (dotCount + 1) % 4
                delay(400)
            }
        }
    }

    private fun stopDotAnimation(finalText: String = "") {
        dotAnimatorJob?.cancel()
        if (finalText.isNotEmpty()) {
            statusText.text = finalText
        }
    }

    private fun parseMarkdown(text: String): android.text.SpannableStringBuilder {
        val spannable = android.text.SpannableStringBuilder()
        val parts = text.split("**")
        for (i in parts.indices) {
            val start = spannable.length
            spannable.append(parts[i])
            if (i % 2 != 0) { // Text inside ** **
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    spannable.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════

    override fun onDestroy() {
        dotAnimatorJob?.cancel()
        typewriterJob?.cancel()
        observerScope.cancel()
        
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


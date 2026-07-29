
package com.tcs.vehicleassistant
import android.content.IntentFilter
import android.content.BroadcastReceiver

import android.Manifest
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.tcs.vehicleassistant.repository.InAppOrchestratorBridge
import com.tcs.vehicleassistant.utils.EmergencyAlarmManager

class LocalLLMActivity : AppCompatActivity() {
    companion object {
        var isTestRunning = false
        var isCloudModelActive = false
        var currentCloudModelName = ""

        /** Defaults for the debug-only model sideload hook; expects `adb reverse tcp:8080 tcp:8080`. */
        private const val DEFAULT_SIDELOAD_URL = "http://localhost:8080/Qwen2.5.litertlm"
        private const val DEFAULT_SIDELOAD_FILE = "Qwen2.5.litertlm"

        fun loadRuntimePrefs(context: Context) {
            DemoSettingsPresets.ensureDefaults(context)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            isCloudModelActive = prefs.getBoolean("cloud_model_active", false)
            currentCloudModelName = prefs.getString("cloud_model_name", "") ?: ""
        }

        fun saveCloudMode(context: Context, active: Boolean, modelName: String) {
            isCloudModelActive = active
            currentCloudModelName = modelName
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("cloud_model_active", active)
                .putString("cloud_model_name", modelName)
                .apply()
        }
    }

    private lateinit var inputText: EditText
    private lateinit var generateButton: Button
    private lateinit var voiceButton: FloatingActionButton
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var tabInference: android.widget.LinearLayout
    private lateinit var tabUseCases: android.widget.ScrollView
    private lateinit var tabSystemPrompt: android.widget.LinearLayout
    private lateinit var etSystemPrompt: android.widget.EditText
    private lateinit var btnSavePrompt: com.google.android.material.button.MaterialButton
    private lateinit var btnResetPrompt: com.google.android.material.button.MaterialButton

    private lateinit var btnPremiumUseCase1: Button
    private lateinit var btnPremiumUseCase2: Button
    private lateinit var btnPremiumUseCase3: Button
    private lateinit var btnPremiumUseCase4: Button
    private lateinit var btnPremiumUseCase5: Button
    private lateinit var btnPremiumUseCase6: Button
    private lateinit var btnPremiumUseCase7: Button
    private lateinit var btnPremiumUseCase8: Button
    private lateinit var btnDownloadModel: Button
    private lateinit var btnLoadModel: Button
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var modelSpinner: Spinner
    private lateinit var spinnerBackend: Spinner
    private lateinit var stopButton: Button
    private lateinit var clearButton: Button
    private lateinit var etWakeWord: EditText
    private lateinit var switchWakeWord: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var llApiKeyContainer: android.widget.LinearLayout
    private lateinit var tvApiKeyLabel: android.widget.TextView
    private lateinit var etApiKey: android.widget.EditText
    private lateinit var tvActiveModel: android.widget.TextView


    
    private lateinit var tts: TextToSpeech
    private lateinit var localAudioManager: com.tcs.vehicleassistant.hardware.IAudioManager


    data class LlmModel(val name: String, val filename: String, val url: String, val size: String, val automotiveContext: String)
    private val supportedModels = listOf(
        LlmModel("SmolLM 135M Instruct", "SmolLM-135M-Instruct.task", "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task", "150MB", "Entry-Level"),
        LlmModel("TinyLlama-1.1B", "TinyLlama-1.1B-Chat-v1.0.task", "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task", "1.1GB", "Entry-Level"),
        LlmModel("Qwen2.5 1.5B (Customized)", "model.litertlm", "", "1.7GB", "Mid-Range"),
        LlmModel("Qwen2.5 1.5B Instruct", "Qwen2.5.litertlm", "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", "1.6GB", "Mid-Range"),
        LlmModel("Gemma 2B INT4", "gemma-2b-it-gpu-int4.bin", "", "1.8GB", "Mid-Range"),
        LlmModel("Gemma 4-E2B IT (Generic)", "gemma-4-E2B-it.litertlm", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "2.5GB", "Mid-Range"),
        LlmModel("Gemma 4-E2B IT (Qualcomm SA8255)", "gemma-4-E2B-it_qualcomm_qcs8275.litertlm", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_qcs8275.litertlm", "2.5GB", "Premium Automotive"),
        LlmModel("Phi-4-mini", "Phi-4-mini-instruct.litertlm", "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm", "3.8GB", "Premium"),
        LlmModel("Llama 3.2 3B Instruct", "Llama-3.2-3B-Instruct.litertlm", "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", "3.2GB", "Premium"),
        LlmModel("Qwen2.5 3B Instruct", "Qwen2.5-3B-Instruct.litertlm", "https://huggingface.co/litert-community/Qwen2.5-3B-Instruct/resolve/main/Qwen2.5-3B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", "3.1GB", "Premium"),
        LlmModel("Gemma 4-E2B IT (Google Tensor G5)", "gemma-4-E2B-it_Google_Tensor_G5.litertlm", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm", "3.6GB", "Premium Pixel"),
        LlmModel("Llama Handoff Minimal", "handoff_litert_runtime_minimal.litertlm", "", "3.6GB", "Premium"),
        LlmModel("Claude 3.5 Sonnet (Cloud)", "claude-3-5-sonnet", "api", "Cloud", "Premium"),
        LlmModel("Gemini 2.5 Flash (Cloud)", "gemini-2.5-flash", "api", "Cloud", "Premium")
    )
    
    var currentModel = supportedModels[2]
    
    // Companion object merged above

    private var MODEL_PATH = ""
    private var isGenerating = false
    private var isVoiceMode = false
    private var alarmJob: Job? = null
    private var timeoutJob: Job? = null
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var orchestratorBridge: InAppOrchestratorBridge? = null
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

        loadRuntimePrefs(this)
        VehicleManager.initialize(this)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i("Soniqo", "Starting Soniqo model download/verification...")
                audio.soniqo.speech.ModelManager.ensureModels(this@LocalLLMActivity)
                Log.i("Soniqo", "Soniqo models are ready!")
            } catch (e: Exception) {
                Log.e("Soniqo", "Failed to ensure Soniqo models: ${e.message}")
            }
        }
        
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        generateButton = findViewById(R.id.generateButton)
        voiceButton = findViewById(R.id.voiceButton)

        com.tcs.vehicleassistant.core.DebugBroadcasts.register(
            this, sideloadModelReceiver, com.tcs.vehicleassistant.core.DebugBroadcasts.ACTION_SIDELOAD_MODEL
        )

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        tabLayout = findViewById(R.id.tabLayout)
        tabInference = findViewById<android.widget.LinearLayout>(R.id.tabInference)
        tabUseCases = findViewById<android.widget.ScrollView>(R.id.tabUseCases)
        tabSystemPrompt = findViewById<android.widget.LinearLayout>(R.id.tabSystemPrompt)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        btnSavePrompt = findViewById(R.id.btnSavePrompt)
        btnResetPrompt = findViewById(R.id.btnResetPrompt)

        btnPremiumUseCase1 = findViewById(R.id.btnPremiumUseCase1)
        btnPremiumUseCase2 = findViewById(R.id.btnPremiumUseCase2)
        btnPremiumUseCase3 = findViewById(R.id.btnPremiumUseCase3)
        btnPremiumUseCase4 = findViewById(R.id.btnPremiumUseCase4)
        btnPremiumUseCase5 = findViewById(R.id.btnPremiumUseCase5)
        btnPremiumUseCase6 = findViewById(R.id.btnPremiumUseCase6)
        btnPremiumUseCase7 = findViewById(R.id.btnPremiumUseCase7)
        btnPremiumUseCase8 = findViewById(R.id.btnPremiumUseCase8)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnLoadModel = findViewById(R.id.btnLoadModel)
        progressBar = findViewById(R.id.progressBar)
        modelSpinner = findViewById(R.id.modelSpinner)
        spinnerBackend = findViewById(R.id.spinnerBackend)
        val spinnerAnimationStyle = findViewById<Spinner>(R.id.spinnerAnimationStyle)
        val spinnerUILayout = findViewById<Spinner>(R.id.spinnerUILayout)
        
        val backendOptions = arrayOf("Auto", "NPU", "GPU", "CPU")
        val backendAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backendOptions)
        spinnerBackend.adapter = backendAdapter
        stopButton = findViewById(R.id.stopButton)
        clearButton = findViewById(R.id.clearButton)
        etWakeWord = findViewById(R.id.etWakeWord)
        switchWakeWord = findViewById(R.id.switchWakeWord)
        val switchAgenticLoop = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAgenticLoop)
        llApiKeyContainer = findViewById(R.id.llApiKeyContainer)
        tvApiKeyLabel = findViewById(R.id.tvApiKeyLabel)
        etApiKey = findViewById(R.id.etApiKey)
        tvActiveModel = findViewById(R.id.tvActiveModel)
        
        val cloudPrefs = getSharedPreferences("llm_prefs", Context.MODE_PRIVATE)
        AnthropicManager.apiKey = cloudPrefs.getString("anthropic_api_key", "") ?: ""
        GeminiManager.apiKey = cloudPrefs.getString("gemini_api_key", "") ?: ""
        
        if (GeminiManager.apiKey.isEmpty() && BuildConfig.GEMINI_API_KEY.isNotEmpty()) {
            GeminiManager.apiKey = BuildConfig.GEMINI_API_KEY
        }
        
        etApiKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val key = s.toString()
                if (currentModel.name.contains("Claude")) {
                    AnthropicManager.apiKey = key
                    cloudPrefs.edit().putString("anthropic_api_key", key).apply()
                } else if (currentModel.name.contains("Gemini")) {
                    GeminiManager.apiKey = key
                    cloudPrefs.edit().putString("gemini_api_key", key).apply()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Re-use test logic when intent action matches
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedModelPath = prefs.getString("selected_model", "")
        if (!savedModelPath.isNullOrEmpty()) {
            MODEL_PATH = savedModelPath
        }

        // Setup Animation Style Spinner
        val animationOptions = arrayOf(
            "Google Assistant (4 Dots)",
            "Polestar Holographic Waveform",
            "Pulsing Aura",
            "Minimalist Edge Bar",
            "Dual-Tone Symmetrical EQ",
            "Zero-G Orb (Holographic)",
            "Levitating Core (Magnetic)",
            "Photo-Optimized Fluid Ribbon (Google)"
        )
        val animAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, animationOptions)
        spinnerAnimationStyle.adapter = animAdapter
        val savedAnimStyle = prefs.getInt("anim_style_pref", 1) // Default to Polestar (1)
        spinnerAnimationStyle.setSelection(savedAnimStyle)
        
        spinnerAnimationStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("anim_style_pref", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Setup UI Layout Style Spinner
        val uiLayoutOptions = arrayOf(
            "Wide Dashboard Strip (Polestar)",
            "Floating Center Pill (Google)",
            "Driver-Side Vertical Panel",
            "Slim Top Banner",
            "Full-Screen Immersive Canvas",
            "Holographic Cyberpunk HUD",
            "Beveled Glass Island (Lucid)",
            "Cinematic Letterbox (Concept)"
        )
        val layoutAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, uiLayoutOptions)
        spinnerUILayout.adapter = layoutAdapter
        val savedLayout = prefs.getInt("ui_layout_pref", 0) // Default to Polestar
        spinnerUILayout.setSelection(savedLayout)

        spinnerUILayout.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("ui_layout_pref", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        if (intent.action == "com.tcs.vehicleassistant.RUN_TESTS" && !isTestRunning) {
            val internalFiles = applicationContext.filesDir.listFiles()?.toList() ?: emptyList()
            val externalFiles = applicationContext.getExternalFilesDir(null)?.listFiles()?.toList() ?: emptyList()
            val llmDir = java.io.File("/data/local/tmp/llm/")
            val tmpDir = java.io.File("/data/local/tmp/")
            val tmpFiles = llmDir.listFiles()?.toList() ?: emptyList()
            val rootTmpFiles = tmpDir.listFiles()?.toList() ?: emptyList()
            val allFiles = internalFiles + externalFiles + tmpFiles + rootTmpFiles
            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            android.util.Log.i("AutomatedTest", "Found models: ${models.joinToString { it.absolutePath }}")
            
            lifecycleScope.launch {
                initLlm()
            }
            isTestRunning = true
            android.util.Log.i("AutomatedTest", "Starting automated test suite...")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runAutomatedTests()
                isTestRunning = false
            }
        }
        
        if (intent.action == "com.tcs.vehicleassistant.DUMP_TEST_RESULTS") {
            val file = java.io.File(getExternalFilesDir(null), "test_results.md")
            if (file.exists()) {
                val lines = file.readLines()
                android.util.Log.i("AutomatedTest", "--- BEGIN TEST RESULTS ---")
                for (line in lines) {
                    android.util.Log.i("AutomatedTest", line)
                }
                android.util.Log.i("AutomatedTest", "--- END TEST RESULTS ---")
            } else {
                android.util.Log.e("AutomatedTest", "test_results.md does not exist!")
            }
        }

        supportActionBar?.title = "MediaPipe Local LLM"
        
        if (intent.getBooleanExtra("auto_trigger_mic", false)) {
            android.util.Log.d("AutomatedTest", "auto_trigger_mic is true, clicking voice button.")
            voiceButton.postDelayed({ voiceButton.performClick() }, 500)
        }
        
        chatAdapter = ChatAdapter(mutableListOf())
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

        // Setup Internet Status Monitor
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val dashInternet = findViewById<android.widget.TextView>(R.id.dashInternet)
        val updateInternetUI = { isOnline: Boolean ->
            runOnUiThread {
                dashInternet.text = if (isOnline) "🌐 Net: Online" else "🌐 Net: Offline"
                dashInternet.setTextColor(android.graphics.Color.parseColor(if (isOnline) "#03DAC6" else "#CF6679"))
            }
        }
        
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        updateInternetUI(capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        
        connectivityManager.registerNetworkCallback(
            android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
                    val isOnline = networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    updateInternetUI(isOnline)
                }
                override fun onLost(network: android.net.Network) { updateInternetUI(false) }
            }
        )

        // Setup TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                applyVoiceSettings()
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        LatencyLogger.log("LocalLLMActivity", "TTS Synthesis Started for $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        LatencyLogger.log("LocalLLMActivity", "TTS Synthesis Done for $utteranceId")
                        if (utteranceId == "QUESTION" || utteranceId == "QUESTION_FINAL") {
                            runOnUiThread {
                                voiceButton.performClick()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                    }
                })
                initOrchestratorBridge()
            }
        }

        // Removed runSystemDiagnostics() to prevent it from executing tools every time the activity starts

        // Setup Permissions
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        val missingPerms = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), 1)
        } else {
            // Disabled background camera start to prevent hardware conflict with AAOS CarBiometricService
            android.util.Log.d("LocalLLMActivity", "Skipped CabinCameraManager auto-start to preserve system camera service stability.")
        }

        // Setup STT using AndroidAudioManager (Vosk)
        localAudioManager = com.tcs.vehicleassistant.hardware.AndroidAudioManager(this)
        localAudioManager.initialize({
            localAudioManager.setRecognitionListener(
                onReadyForSpeech = {
                    runOnUiThread {
                        LatencyLogger.log("LocalLLMActivity", "Speech Recognizer onReadyForSpeech")
                        inputText.setText("")
                    }
                },
                onBeginningOfSpeech = {
                    runOnUiThread { LatencyLogger.log("LocalLLMActivity", "Speech Recognizer onBeginningOfSpeech") }
                },
                onEndOfSpeech = {
                    runOnUiThread {
                        LatencyLogger.log("LocalLLMActivity", "Speech Recognizer onEndOfSpeech")
                        inputText.setText("")
                    }
                },
                onResult = { text ->
                    runOnUiThread {
                        LatencyLogger.log("LocalLLMActivity", "Speech Recognizer onResults")
                        generateText(text, isVoice = true, displayPrompt = text)
                    }
                },
                onEmptyResult = {},
                onError = {
                    runOnUiThread {
                        val msg = "Voice Error: offline engine failed"
                        inputText.setText(msg)
                        chatAdapter.addMessage(ChatMessage(msg, isUser = false))
                    }
                },
                onPartial = {}
            )
        }, {})

        voiceButton.setOnClickListener {
            LatencyLogger.reset()
            LatencyLogger.log("LocalLLMActivity", "Voice Button Clicked")
            AssistantVoiceInteractionService.triggerSession(this)
        }

        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                tabInference.visibility = View.GONE
                tabUseCases.visibility = View.GONE
                tabSystemPrompt.visibility = View.GONE
                
                when (tab?.position) {
                    0 -> tabInference.visibility = View.VISIBLE
                    1 -> tabUseCases.visibility = View.VISIBLE
                    2 -> tabSystemPrompt.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // Setup System Prompt Editor
        lifecycleScope.launch {
            etSystemPrompt.setText(LLMManager.getSystemPrompt(this@LocalLLMActivity))
        }
        
        btnSavePrompt.setOnClickListener {
            val newPrompt = etSystemPrompt.text.toString()
            prefs.edit().putString("system_prompt", newPrompt).apply()
            LLMManager.resetConversation()
            Toast.makeText(this, "System Prompt Saved & Applied!", Toast.LENGTH_SHORT).show()
        }
        
        btnResetPrompt.setOnClickListener {
            lifecycleScope.launch {
                val defaultPrompt = LLMManager.getDefaultSystemPrompt(this@LocalLLMActivity)
                etSystemPrompt.setText(defaultPrompt)
                prefs.edit().remove("system_prompt").apply()
                LLMManager.resetConversation()
                Toast.makeText(this@LocalLLMActivity, "Reset to Default Prompt!", Toast.LENGTH_SHORT).show()
            }
        }


        setupUseCases()
        
        // Custom Spinner Layouts to fix Dark Mode contrast issue
        val adapter = ArrayAdapter(this, R.layout.spinner_item, supportedModels.map { "[${it.automotiveContext}] ${it.name} (${it.size})" })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        modelSpinner.adapter = adapter
        modelSpinner.setSelection(3) // Default to Qwen 2.5 1.5B Instruct
        if (isCloudModelActive) {
            val cloudIndex = supportedModels.indexOfFirst { it.name == currentCloudModelName }
            if (cloudIndex >= 0) modelSpinner.setSelection(cloudIndex)
        }
        
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentModel = supportedModels[position]
                if (currentModel.url == "api") {
                    saveCloudMode(this@LocalLLMActivity, true, currentModel.name)
                    llApiKeyContainer.visibility = View.VISIBLE
                    if (currentModel.name.contains("Claude")) {
                        tvApiKeyLabel.text = "Anthropic API Key:"
                        etApiKey.setText(AnthropicManager.apiKey)
                    } else if (currentModel.name.contains("Gemini")) {
                        tvApiKeyLabel.text = "Gemini API Key:"
                        etApiKey.setText(GeminiManager.apiKey)
                    }
                    updateActiveModelUI()
                    btnDownloadModel.isEnabled = false
                    btnLoadModel.isEnabled = true
                } else {
                    saveCloudMode(this@LocalLLMActivity, false, "")
                    llApiKeyContainer.visibility = View.GONE
                    btnDownloadModel.isEnabled = true
                    btnLoadModel.isEnabled = true
                }
                checkModelExists()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateDashboardUI()
        checkModelExists()

        btnLoadModel.setOnClickListener {
            lifecycleScope.launch {
                initLlm()
            }
        }

        btnDownloadModel.setOnClickListener {
            downloadModel()
        }

        generateButton.setOnClickListener {
            val prompt = inputText.text.toString()
            if (prompt.isNotEmpty()) {
                generateText(prompt, displayPrompt = prompt)
            }
        }

        stopButton.setOnClickListener {
            if (isGenerating) {
                // Cancel ongoing generation but DO NOT destroy the engine
                LLMManager.conversation?.cancelProcess()
                chatAdapter.updateLastMessage("\n\n[Inference stopped by user]")
                resetControls()
            } else {
                // If not generating, clear chat and reset conversation state to grab fresh vehicle context
                chatAdapter.clearMessages()
                LLMManager.resetConversation()
            }
        }

        clearButton.setOnClickListener {
            if (isGenerating) {
                LLMManager.conversation?.cancelProcess()
            }
            chatAdapter.clearMessages()
            LLMManager.resetConversation()
            resetControls()
        }
        
        val btnSyncTime = findViewById<Button>(R.id.btnSyncTime)
        btnSyncTime.setOnClickListener {
            btnSyncTime.isEnabled = false
            btnSyncTime.text = "Syncing..."
            Thread {
                try {
                    val url = java.net.URL("https://clients3.google.com/generate_204")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val dateHeader = conn.getHeaderField("Date")
                    if (dateHeader != null) {
                        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                        val date = format.parse(dateHeader)
                        if (date != null) {
                            val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                            am.setTime(date.time)
                            runOnUiThread {
                                Toast.makeText(this@LocalLLMActivity, "Time synced to $date", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this@LocalLLMActivity, "No Date header found", Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    Log.e("LocalLLMActivity", "Failed to sync time", e)
                    runOnUiThread {
                        Toast.makeText(this@LocalLLMActivity, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    runOnUiThread {
                        btnSyncTime.isEnabled = true
                        btnSyncTime.text = "Sync Time"
                    }
                }
            }.start()
        }
        
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            showTelemetrySettingsDialog()
        }

        val btnOfflineTTS = findViewById<Button>(R.id.btnOfflineTTS)
        btnOfflineTTS.setOnClickListener {
            val intent = Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "TTS Settings not found on this device.", Toast.LENGTH_SHORT).show()
            }
        }

        val btnCockpitAI = findViewById<Button>(R.id.btnCockpitAI)
        btnCockpitAI?.setOnClickListener {
            val intent = Intent(this, CockpitAwarenessActivity::class.java)
            startActivity(intent)
        }
        
        // Wake Word Initialization
        val currentWakeWord = prefs.getString(com.tcs.vehicleassistant.core.AssistantConfig.Prefs.WAKE_WORD, com.tcs.vehicleassistant.core.AssistantConfig.WakeWord.DEFAULT_WAKE_WORD)
        etWakeWord.setText(currentWakeWord)
        
        val isWakeWordEnabled = prefs.getBoolean(
            com.tcs.vehicleassistant.core.AssistantConfig.Prefs.WAKE_WORD_ENABLED, false
        )
        switchWakeWord.isChecked = isWakeWordEnabled
        if (isWakeWordEnabled) {
            try { startService(Intent(this, WakeWordService::class.java)) } catch (e: Exception) {}
        }
        
        switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(
                com.tcs.vehicleassistant.core.AssistantConfig.Prefs.WAKE_WORD_ENABLED, isChecked
            ).apply()
            val intent = Intent(this, WakeWordService::class.java)
            if (isChecked) {
                try { startService(intent) } catch (e: Exception) {}
            } else {
                try { stopService(intent) } catch (e: Exception) {}
            }
        }
        
        // Agentic Loop Initialization
        val isAgenticLoopEnabled = prefs.getBoolean("agentic_loop_enabled", true)
        switchAgenticLoop.isChecked = isAgenticLoopEnabled
        switchAgenticLoop.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("agentic_loop_enabled", isChecked).apply()
        }
        
        // Companion Mode Initialization
        val switchCompanionMode = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchCompanionMode)
        val isCompanionModeEnabled = prefs.getBoolean("companion_mode_enabled", true)
        switchCompanionMode.isChecked = isCompanionModeEnabled
        switchCompanionMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("companion_mode_enabled", isChecked).apply()
        }
        
        etWakeWord.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(com.tcs.vehicleassistant.core.AssistantConfig.Prefs.WAKE_WORD, s.toString().lowercase()).apply()
                if (switchWakeWord.isChecked) {
                    val intent = Intent(this@LocalLLMActivity, WakeWordService::class.java)
                    try { startService(intent) } catch (e: Exception) {}
                }
            }
        })

        com.tcs.vehicleassistant.core.DebugBroadcasts.register(
            this, diagnosticReceiver, com.tcs.vehicleassistant.core.DebugBroadcasts.ACTION_DIAGNOSTICS_DUMP
        )
        com.tcs.vehicleassistant.core.DebugBroadcasts.register(
            this, testQueryReceiver, com.tcs.vehicleassistant.core.DebugBroadcasts.ACTION_TEST_QUERY
        )
    }

    private fun applyVoiceSettings() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val rate = prefs.getFloat("voice_rate", 1.05f)
        val pitch = prefs.getFloat("voice_pitch", 1.05f)
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
    }

    private fun showTelemetrySettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_telemetry_settings, null)
        val etSpeed = dialogView.findViewById<android.widget.EditText>(R.id.etSpeed)
        val etKvCache = dialogView.findViewById<android.widget.EditText>(R.id.etKvCache)

        etSpeed.setText(VehicleManager.getRealSpeed().toString())
        
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val currentKvCache = prefs.getInt("max_tokens", 4096)
        val currentAutoFlush = prefs.getInt("auto_flush", 25)
        val currentLocationOverride = prefs.getString(LocationManager.PREF_LOCATION_OVERRIDE, "") ?: ""
        val currentMechanicName = prefs.getString("mechanic_name", "Mechanic") ?: "Mechanic"
        val currentMechanicNum = prefs.getString("mechanic_number", "555-0199") ?: "555-0199"
        val currentDiningPref = prefs.getString("dining_pref", "Pure Vegetarian") ?: "Pure Vegetarian"
        val cloudFallbackEnabled = prefs.getBoolean("cloud_fallback_enabled", true)

        etKvCache.setText(currentKvCache.toString())
        val etAutoFlush = dialogView.findViewById<android.widget.EditText>(R.id.etAutoFlush)
        val switchCloudFallback = dialogView.findViewById<android.widget.Switch>(R.id.switchCloudFallback)
        switchCloudFallback.isChecked = cloudFallbackEnabled
        etAutoFlush.setText(currentAutoFlush.toString())
        
        val etLocationOverride = dialogView.findViewById<android.widget.EditText>(R.id.etLocationOverride)
        etLocationOverride.setText(currentLocationOverride)

        val etMechanicName = dialogView.findViewById<android.widget.EditText>(R.id.etMechanicName)
        val etMechanicNumber = dialogView.findViewById<android.widget.EditText>(R.id.etMechanicNumber)
        val etDiningPref = dialogView.findViewById<android.widget.EditText>(R.id.etDiningPref)
        etMechanicName.setText(currentMechanicName)
        etMechanicNumber.setText(currentMechanicNum)
        etDiningPref.setText(currentDiningPref)

        val spinnerDemoPreset = dialogView.findViewById<Spinner>(R.id.spinnerDemoPreset)
        val spinnerLocationSource = dialogView.findViewById<Spinner>(R.id.spinnerLocationSource)
        val tvResolvedLocation = dialogView.findViewById<android.widget.TextView>(R.id.tvResolvedLocation)
        val btnApplyDemoPreset = dialogView.findViewById<Button>(R.id.btnApplyDemoPreset)

        val presetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            DemoSettingsPresets.ALL.map { it.displayName }
        )
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDemoPreset.adapter = presetAdapter

        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            LocationManager.Source.entries.map { it.label }
        )
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLocationSource.adapter = sourceAdapter

        val selectedPreset = DemoSettingsPresets.getSelected(this)
        val selectedPresetIndex = DemoSettingsPresets.ALL.indexOfFirst { it.id == selectedPreset.id }.coerceAtLeast(0)
        spinnerDemoPreset.setSelection(selectedPresetIndex)

        val selectedSource = LocationManager.Source.fromPref(prefs.getString(LocationManager.PREF_LOCATION_SOURCE, null))
        val selectedSourceIndex = LocationManager.Source.entries.indexOf(selectedSource).coerceAtLeast(0)
        spinnerLocationSource.setSelection(selectedSourceIndex)

        fun refreshResolvedLocation() {
            val source = LocationManager.Source.entries[spinnerLocationSource.selectedItemPosition]
            val preset = DemoSettingsPresets.ALL[spinnerDemoPreset.selectedItemPosition]
            val preview = when (source) {
                LocationManager.Source.MANUAL -> {
                    val manual = etLocationOverride.text.toString().trim().ifBlank { preset.coordinatesString() }
                    "${preset.cityName} ($manual) via ${source.label}"
                }
                LocationManager.Source.PRESET -> "${preset.cityName} (${preset.coordinatesString()}) via ${source.label}"
                LocationManager.Source.DEVICE -> LocationManager.getLocationStatus(this)
            }
            tvResolvedLocation.text = "Resolved: $preview"
        }
        refreshResolvedLocation()

        btnApplyDemoPreset.setOnClickListener {
            val preset = DemoSettingsPresets.ALL[spinnerDemoPreset.selectedItemPosition]
            DemoSettingsPresets.apply(this, preset)
            etLocationOverride.setText(preset.coordinatesString())
            etMechanicName.setText(preset.mechanicName)
            etMechanicNumber.setText(preset.mechanicNumber)
            etDiningPref.setText(preset.diningPref)
            refreshResolvedLocation()
            val switchCompanionMode = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchCompanionMode)
            val switchAgenticLoop = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAgenticLoop)
            switchCompanionMode?.isChecked = preset.companionMode
            switchAgenticLoop?.isChecked = preset.agenticLoop
            Toast.makeText(this, "Applied ${preset.displayName} defaults", Toast.LENGTH_SHORT).show()
        }

        spinnerDemoPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshResolvedLocation()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerLocationSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshResolvedLocation()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Voice Settings
        val etSpeakingRate = dialogView.findViewById<android.widget.EditText>(R.id.etSpeakingRate)
        val etPitch = dialogView.findViewById<android.widget.EditText>(R.id.etPitch)
        val etVolume = dialogView.findViewById<android.widget.EditText>(R.id.etVolume)
        val etEmotion = dialogView.findViewById<android.widget.EditText>(R.id.etEmotion)
        val etEmphasis = dialogView.findViewById<android.widget.EditText>(R.id.etEmphasis)
        val etPauses = dialogView.findViewById<android.widget.EditText>(R.id.etPauses)
        val etBreath = dialogView.findViewById<android.widget.EditText>(R.id.etBreath)
        val etIntensity = dialogView.findViewById<android.widget.EditText>(R.id.etIntensity)
        val etReverb = dialogView.findViewById<android.widget.EditText>(R.id.etReverb)

        etSpeakingRate.setText(prefs.getFloat("voice_rate", 1.0f).toString())
        etPitch.setText(prefs.getFloat("voice_pitch", 1.0f).toString())
        etVolume.setText(prefs.getFloat("voice_volume", -1.0f).toString())
        etEmotion.setText(prefs.getString("voice_emotion", "empathetic"))
        etEmphasis.setText(prefs.getString("voice_emphasis", "moderate"))
        etPauses.setText(prefs.getInt("voice_pauses", 200).toString())
        etBreath.setText(prefs.getInt("voice_breath", 120).toString())
        etIntensity.setText(prefs.getFloat("voice_intensity", 0.4f).toString())
        etReverb.setText(prefs.getString("voice_reverb", "none"))

        android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newSpeed = etSpeed.text.toString().toFloatOrNull()
                val newKvCache = etKvCache.text.toString().toIntOrNull()
                val newAutoFlush = etAutoFlush.text.toString().toIntOrNull()

                if (newSpeed != null) VehicleManager.setMockSpeed(newSpeed)
                
                prefs.edit().apply {
                    putString(LocationManager.PREF_LOCATION_OVERRIDE, etLocationOverride.text.toString().trim())
                    putString(LocationManager.PREF_DEMO_PRESET, DemoSettingsPresets.ALL[spinnerDemoPreset.selectedItemPosition].id)
                    putString(
                        LocationManager.PREF_LOCATION_SOURCE,
                        LocationManager.Source.entries[spinnerLocationSource.selectedItemPosition].prefValue
                    )
                    putString(
                        LocationManager.PREF_DEMO_CITY,
                        DemoSettingsPresets.ALL[spinnerDemoPreset.selectedItemPosition].cityName
                    )
                    putString("mechanic_name", etMechanicName.text.toString())
                    putString("mechanic_number", etMechanicNumber.text.toString())
                    putString("dining_pref", etDiningPref.text.toString())
                    if (newKvCache != null) putInt("max_tokens", newKvCache)
                    if (newAutoFlush != null) putInt("auto_flush", newAutoFlush)
                    putBoolean("cloud_fallback_enabled", switchCloudFallback.isChecked)
                    
                    // Save Voice Settings
                    putFloat("voice_rate", etSpeakingRate.text.toString().toFloatOrNull() ?: 1.0f)
                    putFloat("voice_pitch", etPitch.text.toString().toFloatOrNull() ?: 1.0f)
                    putFloat("voice_volume", etVolume.text.toString().toFloatOrNull() ?: -1.0f)
                    putString("voice_emotion", etEmotion.text.toString())
                    putString("voice_emphasis", etEmphasis.text.toString())
                    putInt("voice_pauses", etPauses.text.toString().toIntOrNull() ?: 200)
                    putInt("voice_breath", etBreath.text.toString().toIntOrNull() ?: 120)
                    putFloat("voice_intensity", etIntensity.text.toString().toFloatOrNull() ?: 0.4f)
                    putString("voice_reverb", etReverb.text.toString())
                    apply()
                }

                applyVoiceSettings()
                updateDashboardUI()
                Toast.makeText(this, "Settings Updated", Toast.LENGTH_SHORT).show()

                if (newKvCache != null && newKvCache != currentKvCache) {
                    Toast.makeText(this, "Re-initializing LLM with new KV Cache...", Toast.LENGTH_SHORT).show()
                    
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        LLMManager.autoInitialize(this@LocalLLMActivity, force = true)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateDashboardUI() {
        val dashTemp = findViewById<android.widget.TextView>(R.id.dashTemp)
        val dashSpeed = findViewById<android.widget.TextView>(R.id.dashSpeed)
        val dashHeater = findViewById<android.widget.TextView>(R.id.dashHeater)
        
        runOnUiThread {
            val seatHeaterLevel = VehicleManager.getRealSeatHeaterLevel()
            dashTemp?.text = "🌡️ Temp: ${VehicleManager.getRealTemperature()}°F"
            dashSpeed?.text = "🏎️ Speed: ${VehicleManager.getRealSpeed()}mph"
            dashHeater?.text = "💺 Heater: ${if (seatHeaterLevel > 0) "Level $seatHeaterLevel" else "Off"}"
        }
    }
    




    private fun stopEmergencyAlarm() {
        alarmJob?.cancel()
        alarmJob = null
        EmergencyAlarmManager.stop()
    }

    private fun initOrchestratorBridge() {
        if (orchestratorBridge != null) return
        orchestratorBridge = InAppOrchestratorBridge(this, localAudioManager, lifecycleScope).apply {
            onThinking = {
                runOnUiThread {
                    if (isGenerating) chatAdapter.replaceLastMessage("Thinking...")
                }
            }
            onStreaming = { msg ->
                runOnUiThread {
                    if (!isGenerating) return@runOnUiThread
                    chatAdapter.replaceLastMessage(msg)
                    chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }
            onSpeaking = { msg ->
                runOnUiThread {
                    chatAdapter.replaceLastMessage(msg)
                    chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    updateDashboardUI()
                    resetControls()
                }
            }
            onError = { msg ->
                runOnUiThread {
                    chatAdapter.replaceLastMessage(msg)
                    resetControls()
                }
            }
            onLaunchIntent = { intent ->
                runOnUiThread {
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@LocalLLMActivity, "Could not launch app.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        updateActiveModelUI()
    }

    private fun updateActiveModelUI() {
        if (isCloudModelActive) {
            tvActiveModel.text = "Active Model: $currentCloudModelName *"
            generateButton.isEnabled = true
        } else if (LLMManager.engine != null && LLMManager.currentModelPath.isNotEmpty()) {
            val loadedModelName = supportedModels.find { LLMManager.currentModelPath.endsWith(it.filename) }?.name ?: LLMManager.currentModelPath.substringAfterLast("/")
            tvActiveModel.text = "Active Model: $loadedModelName *"
            generateButton.isEnabled = true
        } else {
            tvActiveModel.text = "Active Model: None"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("auto_trigger_mic", false) == true) {
            android.util.Log.d("AutomatedTest", "onNewIntent auto_trigger_mic is true, clicking voice button.")
            val voiceButton = findViewById<android.widget.ImageButton>(R.id.voiceButton)
            voiceButton.postDelayed({ voiceButton.performClick() }, 500)
        }
    }

    /** The wake-word toggle in settings; the microphone service must not outlive it being off. */
    private fun isWakeWordEnabled(): Boolean =
        getSharedPreferences(com.tcs.vehicleassistant.core.AssistantConfig.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(com.tcs.vehicleassistant.core.AssistantConfig.Prefs.WAKE_WORD_ENABLED, false)

    override fun onStop() {
        super.onStop()
        // Restart background wake word listening when the UI hides
        if (!isWakeWordEnabled()) return
        val intent = Intent(this, WakeWordService::class.java)
        intent.action = com.tcs.vehicleassistant.core.AssistantConfig.WakeWordAction.RESTART
        try {
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.w("WakeWord", "Could not resume wake word listening", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val cameraIndex = permissions.indexOf(android.Manifest.permission.CAMERA)
            if (cameraIndex != -1 && grantResults.getOrNull(cameraIndex) == PackageManager.PERMISSION_GRANTED) {
                com.tcs.vehicleassistant.hardware.CabinCameraManager.startCamera(this, androidx.lifecycle.ProcessLifecycleOwner.get())
            }
            val audioIndex = permissions.indexOf(android.Manifest.permission.RECORD_AUDIO)
            if (audioIndex != -1 &&
                grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED &&
                isWakeWordEnabled()
            ) {
                // Restart WakeWordService to bind to the microphone now that permission is granted
                val serviceIntent = Intent(this, WakeWordService::class.java)
                serviceIntent.action = com.tcs.vehicleassistant.core.AssistantConfig.WakeWordAction.RESTART
                startForegroundService(serviceIntent)
            }
        }
    }

    override fun onDestroy() {
        stopEmergencyAlarm()
        orchestratorBridge?.destroy()
        orchestratorBridge = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (::localAudioManager.isInitialized) {
            localAudioManager.shutdown()
        }
        try {
            VehicleManager.cleanup()
        } catch (e: Exception) {
            Log.e("LocalLLMActivity", "Failed to cleanup VehicleManager", e)
        }
        super.onDestroy()
        com.tcs.vehicleassistant.core.DebugBroadcasts.unregister(this, diagnosticReceiver)
        com.tcs.vehicleassistant.core.DebugBroadcasts.unregister(this, testQueryReceiver)
        com.tcs.vehicleassistant.core.DebugBroadcasts.unregister(this, sideloadModelReceiver)
    }

    private fun checkModelExists() {
        if (isCloudModelActive) {
            MODEL_PATH = "cloud_api"
            chatAdapter.addMessage(ChatMessage("Cloud API selected.\nEnsure you have entered a valid API Key and have internet access.", isUser = false))
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            
            generateButton.isEnabled = false
            btnLoadModel.isEnabled = true
            btnLoadModel.text = "Connect to Cloud"
            btnDownloadModel.isEnabled = false
            return
        }

        btnLoadModel.text = "Load Model"
        btnDownloadModel.isEnabled = true

        val internalDir = applicationContext.filesDir
        val externalDir = applicationContext.getExternalFilesDir(null)
        val tmpDir = java.io.File("/data/local/tmp/")
        
        val internalFiles = internalDir.listFiles()?.toList() ?: emptyList()
        val externalFiles = externalDir?.listFiles()?.toList() ?: emptyList()
        val llmDir = java.io.File("/data/local/tmp/llm/")
        val tmpFiles = llmDir.listFiles()?.toList() ?: emptyList()
        val rootTmpFiles = tmpDir.listFiles()?.toList() ?: emptyList()
        val allFiles = internalFiles + externalFiles + tmpFiles + rootTmpFiles
        
        var modelFile = allFiles.firstOrNull { it.name == currentModel.filename }
        
        // Dynamic Fallback: If exact filename is not found, use any existing .bin, .task, or .litertlm file
        if (modelFile == null) {
            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            modelFile = models.find { it.name.contains("handoff", ignoreCase = true) }
                ?: models.find { it.name.contains("Qwen", ignoreCase = true) }
                ?: models.find { it.name.contains("gemma", ignoreCase = true) }
                ?: models.firstOrNull()
            if (modelFile != null) {
                // Try to find a matching LlmModel for this file to update the UI
                val matchingModel = supportedModels.find { it.filename == modelFile!!.name }
                if (matchingModel != null) {
                    currentModel = matchingModel
                }
            }
        }

        if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
            MODEL_PATH = modelFile.absolutePath
            chatAdapter.addMessage(ChatMessage("Model found locally:\n${modelFile.name}\nClick 'Load Model' to initialize.", isUser = false))
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            
            generateButton.isEnabled = false
            btnLoadModel.isEnabled = true
        } else {
            MODEL_PATH = java.io.File(externalDir ?: internalDir, currentModel.filename).absolutePath
            val allNames = allFiles.map { it.name }.joinToString(", ").takeIf { it.isNotEmpty() } ?: "empty"
            android.util.Log.i("AutomatedTest", "No model found. internalDir: ${internalDir.absolutePath}, externalDir: ${externalDir?.absolutePath}, allNames: $allNames")
            chatAdapter.addMessage(ChatMessage("No model found.\nPath checked: ${externalDir?.absolutePath}\nContents: $allNames\n\nPlease click 'Download' or push via ADB.", isUser = false))
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            
            generateButton.isEnabled = false
            btnLoadModel.isEnabled = false
        }
    }

    private fun setupUseCases() {
        val executeScenario = { prompt: String ->
            inputText.setText(prompt)
            tabLayout.getTabAt(0)?.select()
            if (LLMManager.engine == null) {
                chatAdapter.addMessage(ChatMessage("System: Please load a model first before executing a scenario.", isUser = false))
                chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            } else if (!isGenerating) {
                generateText(prompt, displayPrompt = prompt)
            }
        }

        btnPremiumUseCase1.setOnClickListener {
            executeScenario("I'm running low on gas and my kids are hungry. Find the nearest rest stop or family restaurant along my current route on I-5.")
        }
        btnPremiumUseCase2.setOnClickListener {
            executeScenario("The check engine light just came on, and the dashboard says OBD Code P0420. Is it safe to drive 50 more miles?")
        }
        btnPremiumUseCase3.setOnClickListener {
            executeScenario("I'm freezing and the sun is glaring right into my eyes. Provide the exact JSON commands to adjust the HVAC and sunshades.")
        }
        btnPremiumUseCase4.setOnClickListener {
            executeScenario("Sensors indicate the driver is falling asleep!")
        }
        btnPremiumUseCase5.setOnClickListener {
            executeScenario("Play some relaxing jazz music and turn the volume down a bit.")
        }
        btnPremiumUseCase6.setOnClickListener {
            executeScenario("Read my last text message and reply that I am driving and will be there in 15 minutes.")
        }
        btnPremiumUseCase7.setOnClickListener {
            executeScenario("Roll down all the windows and set the ambient lighting to a calming blue.")
        }
        btnPremiumUseCase8.setOnClickListener {
            executeScenario("How do I enable adaptive cruise control on the highway?")
        }
    }

    private suspend fun initLlm(force: Boolean = false) = withContext(Dispatchers.Main) {
        if (isCloudModelActive) {
            chatAdapter.addMessage(ChatMessage("Connected to Cloud API: ${currentModel.name}. Ready for inference.", isUser = false))
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            generateButton.isEnabled = true
            btnLoadModel.isEnabled = false
            return@withContext
        }

        val backendChoice = spinnerBackend.selectedItem.toString()
        
        // Save to SharedPreferences for AssistantSession (Voice Overlay)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("selected_model", MODEL_PATH)
            .putString("backend_choice", backendChoice)
            .apply()
            
        chatAdapter.addMessage(ChatMessage("Loading Model from $MODEL_PATH...\nBackend: $backendChoice\nThis may take a minute.", isUser = false))
        generateButton.isEnabled = false

        LLMManager.initialize(applicationContext, MODEL_PATH, force, backendChoice, object : LLMManager.InitCallback {
            override fun onSuccess() {
                chatAdapter.addMessage(ChatMessage("Model Loaded successfully! Ready for inference.", isUser = false))
                chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                generateButton.isEnabled = true
                btnLoadModel.isEnabled = false
                // Removed prewarm call to optimize startup time
            }

            override fun onError(e: Exception) {
                chatAdapter.addMessage(ChatMessage("Failed to load model from $MODEL_PATH.\nException: ${e.message}", isUser = false))
            }
        })
    }
    
    private fun resetControls() {
        isGenerating = false
        generateButton.isEnabled = true
        voiceButton.isEnabled = true
        stopButton.isEnabled = false
        stopEmergencyAlarm()
        val text = inputText.text.toString()
        if (text == "Listening..." || text == "Processing Voice...") {
            inputText.setText("")
        }
    }

    private fun generateText(prompt: String, isVoice: Boolean = false, displayPrompt: String = "") {
        if (isGenerating) return
        
        if (prompt.trim().lowercase() == "/diagnostics") {
            chatAdapter.addMessage(ChatMessage("/diagnostics", isUser = true))
            if (!isVoice) {
                inputText.setText("")
            }
            
            // Diagnostics queries the hardware which can take a moment, so run off the Main thread
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val report = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().runSystemDiagnostics(this@LocalLLMActivity)
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    chatAdapter.addMessage(ChatMessage(report, isUser = false, isStreaming = false))
                    chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }
            return
        }
        
        generateButton.isEnabled = false
        voiceButton.isEnabled = false
        stopButton.isEnabled = true
        isGenerating = true
        
        lastResponseBuilder.clear()
        
        val actualDisplay = if (isVoice) displayPrompt else prompt
        chatAdapter.addMessage(ChatMessage(actualDisplay, isUser = true))
        
        if (!isVoice) {
            inputText.setText("")
        }
        
        // Add empty bubble for model
        chatAdapter.addMessage(ChatMessage("", isUser = false, isStreaming = true))
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        
        if (!isCloudModelActive && !LLMManager.isReady()) {
            chatAdapter.replaceLastMessage("System: Please click 'Load Model' before sending a prompt.")
            resetControls()
            return
        }

        initOrchestratorBridge()
        orchestratorBridge?.enableTts = isVoice
        isVoiceMode = isVoice
        orchestratorBridge?.handleQuery(prompt)
    }

    private suspend fun runAutomatedTests() {
        android.util.Log.i("AutomatedTest", "Initializing 200 Comprehensive Tests...")
        while (MODEL_PATH.isEmpty()) {
            kotlinx.coroutines.delay(100)
        }
        
        if (LLMManager.engine == null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    LLMManager.initialize(this@LocalLLMActivity, MODEL_PATH)
                    android.util.Log.i("AutomatedTest", "Model loaded successfully.")
                } catch (e: Exception) {
                    android.util.Log.e("AutomatedTest", "Failed to load model: ${e.message}")
                }
            }
        }
        
        // Ensure conversation is initialized before starting
        if (LLMManager.conversation == null) {
            LLMManager.resetConversation()
        }
        
        var passed = 0
        var failed = 0
        val resultsBuilder = java.lang.StringBuilder()
        resultsBuilder.append("# Comprehensive Automotive AI Test Results\n\n")
        resultsBuilder.append("| # | Prompt | Expected Tool | Result | Output |\n")
        resultsBuilder.append("|---|---|---|---|---|\n")

        val totalTests = AutomatedTestSuite.testCases.size
        
        for ((index, testCase) in AutomatedTestSuite.testCases.withIndex()) {
            val query = testCase.prompt
            val expected = testCase.expectedToolPrefix
            var status = "FAIL"
            var outputSnippet = "Timeout/Error"
            
            try {
                val deferred = kotlinx.coroutines.CompletableDeferred<String>()
                val lastResponseBuilder = java.lang.StringBuilder()
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val isFirst = LLMManager.isFirstMessage
                    val finalQuery = if (isFirst) {
                        LLMManager.isFirstMessage = false
                        LLMManager.getSystemPrompt(applicationContext, query) + "\nUser: " + query
                    } else {
                        "User: $query"
                    }
                    
                    android.util.Log.i("AutomatedTest", "Conversation is null: ${LLMManager.conversation == null}")
                    
                    LLMManager.conversation!!.sendMessageAsync(
                        com.google.ai.edge.litertlm.Contents.of(com.google.ai.edge.litertlm.Content.Text(finalQuery)),
                        object : com.google.ai.edge.litertlm.MessageCallback {
                            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                                lastResponseBuilder.append(message.toString())
                            }

                            override fun onDone() {
                                deferred.complete(lastResponseBuilder.toString())
                            }

                            override fun onError(throwable: Throwable) {
                                deferred.completeExceptionally(throwable)
                            }
                        },
                        emptyMap()
                    )
                }
                
                val fullResponse = kotlinx.coroutines.withTimeout(120_000) {
                    deferred.await()
                }
                
                outputSnippet = fullResponse.replace("\n", " ").take(100)
                if (fullResponse.contains(expected, ignoreCase = true)) {
                    status = "PASS"
                    passed++
                } else {
                    status = "FAIL"
                    failed++
                }
                
                val testPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val autoFlushInterval = testPrefs.getInt("auto_flush_interval", 3)
                
                // Clear cache if we are getting close to 512 context limit
                if (index > 0 && index % autoFlushInterval == 0) {
                   kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                       LLMManager.resetConversation()
                   }
                }
                
                kotlinx.coroutines.delay(200) 
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                status = "FAIL"
                outputSnippet = "Timeout (120s)"
                failed++
            } catch (e: Exception) {
                status = "FAIL"
                outputSnippet = "Model Crashed: ${e.message}"
                failed++
            }
            
            android.util.Log.i("AutomatedTest", "Test ${index+1}/$totalTests -> $status. Expected: $expected")
            resultsBuilder.append("| ${index+1} | $query | $expected | $status | $outputSnippet |\n")
        }
        
        val reportHeader = "Total Tests: $totalTests\nPass: $passed\nFail: $failed\n\n"
        val finalReport = reportHeader + resultsBuilder.toString()
        
        try {
            val file = java.io.File(getExternalFilesDir(null), "test_results.md")
            file.writeText(finalReport)
            android.util.Log.i("AutomatedTest", "Test suite complete. Results written to ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("AutomatedTest", "Failed to write report", e)
        }
    }

    private fun downloadModel() {
        btnDownloadModel.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        chatAdapter.addMessage(ChatMessage("Starting download (${currentModel.size})... Please keep the app open.", isUser = false))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var finalUrl = currentModel.url
                var connection: java.net.HttpURLConnection
                var redirects = 0
                
                while (true) {
                    val url = java.net.URL(finalUrl)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connect()
                    
                    val status = connection.responseCode
                    if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                        status == java.net.HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307 || status == 308) {
                        finalUrl = connection.getHeaderField("Location")
                        redirects++
                        if (redirects > 10) throw Exception("Too many redirects")
                    } else {
                        break
                    }
                }

                val fileLength = connection.contentLengthLong
                val input = java.io.BufferedInputStream(connection.inputStream)
                val output = java.io.FileOutputStream(MODEL_PATH)

                val data = ByteArray(1024 * 64)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) {
                                progressBar.progress = progress
                            }
                        }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = android.view.View.GONE
                    chatAdapter.addMessage(ChatMessage("Download complete! Initializing model...", isUser = false))
                }

                initLlm()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = android.view.View.GONE
                    btnDownloadModel.isEnabled = true
                    chatAdapter.addMessage(ChatMessage("Download failed: ${e.message}", isUser = false))
                }
            }
        }
    }
    private val diagnosticReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val report = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().runSystemDiagnostics(this@LocalLLMActivity)
                android.util.Log.i("AutomatedTest", "\n\n=================== DIAGNOSTIC DUMP ===================\n$report\n========================================================\n\n")
            }
        }
    }

    private val testQueryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val query = intent?.getStringExtra("query")
            if (!query.isNullOrEmpty()) {
                android.util.Log.i("AutomatedTest", "Received automated test query: $query")
                generateText(query)
            }
        }
    }

    /**
     * Pulls a `.litertlm` model from a host-side HTTP server reachable over `adb reverse`, which is
     * the only way to get a multi-gigabyte file into this app's private storage on a device where
     * SELinux blocks `adb push` into `/data/data`. Debug builds only.
     */
    private val sideloadModelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val url = intent.getStringExtra("url") ?: DEFAULT_SIDELOAD_URL
            val fileName = intent.getStringExtra("file") ?: DEFAULT_SIDELOAD_FILE
            Thread {
                try {
                    Log.i("LLMManager", "Sideloading model from $url")
                    java.net.URL(url).openStream().use { input ->
                        java.io.File(context.filesDir, fileName).outputStream().use(input::copyTo)
                    }
                    Log.i("LLMManager", "Sideload of $fileName complete.")
                } catch (e: Exception) {
                    Log.e("LLMManager", "Sideload of $fileName failed", e)
                }
            }.start()
        }
    }
}

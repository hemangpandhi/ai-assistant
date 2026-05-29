package com.example.gemininano

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

class LocalLLMActivity : AppCompatActivity() {
    companion object {
        var isTestRunning = false
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
    private lateinit var switchCpuBackend: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var stopButton: Button
    private lateinit var clearButton: Button
    private lateinit var etWakeWord: EditText
    private lateinit var switchWakeWord: com.google.android.material.switchmaterial.SwitchMaterial


    
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer


    data class LlmModel(val name: String, val filename: String, val url: String, val size: String, val automotiveContext: String)
    private val supportedModels = listOf(
        LlmModel("SmolLM 135M Instruct", "SmolLM-135M-Instruct.task", "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task", "150MB", "Entry-Level"),
        LlmModel("TinyLlama-1.1B", "TinyLlama-1.1B-Chat-v1.0.task", "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task", "1.1GB", "Entry-Level"),
        LlmModel("Qwen2.5 1.5B Instruct", "Qwen2.5-1.5B-Instruct.litertlm", "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", "1.6GB", "Mid-Range"),
        LlmModel("Gemma 2B INT4", "gemma-2b-it-gpu-int4.bin", "", "1.8GB", "Mid-Range"),
        LlmModel("Gemma 4-E2B IT", "gemma-4-E2B-it.litertlm", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "2.5GB", "Mid-Range"),
        LlmModel("Phi-4-mini", "Phi-4-mini-instruct.litertlm", "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm", "3.8GB", "Premium")
    )
    
    private var currentModel = supportedModels[2] 

    private var MODEL_PATH = ""
    private var isGenerating = false
    private var isVoiceMode = false
    private var alarmJob: Job? = null
    private var timeoutJob: Job? = null
    private var lastResponseBuilder = java.lang.StringBuilder()
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        VehicleManager.initialize(this)
        
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        generateButton = findViewById(R.id.generateButton)
        voiceButton = findViewById(R.id.voiceButton)
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
        switchCpuBackend = findViewById(R.id.switchCpuBackend)
        stopButton = findViewById(R.id.stopButton)
        clearButton = findViewById(R.id.clearButton)
        etWakeWord = findViewById(R.id.etWakeWord)
        switchWakeWord = findViewById(R.id.switchWakeWord)

        // Re-use test logic when intent action matches
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedModelPath = prefs.getString("selected_model", "")
        if (!savedModelPath.isNullOrEmpty()) {
            MODEL_PATH = savedModelPath
        }
        
        if (intent.action == "com.example.gemininano.RUN_TESTS" && !isTestRunning) {
            val internalFiles = applicationContext.filesDir.listFiles()?.toList() ?: emptyList()
            val externalFiles = applicationContext.getExternalFilesDir(null)?.listFiles()?.toList() ?: emptyList()
            val tmpFiles = java.io.File("/data/local/tmp/").listFiles()?.toList() ?: emptyList()
            val allFiles = internalFiles + externalFiles + tmpFiles
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
        
        if (intent.action == "com.example.gemininano.DUMP_TEST_RESULTS") {
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
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "QUESTION" || utteranceId == "QUESTION_FINAL") {
                            runOnUiThread {
                                voiceButton.performClick()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
            }
        }

        // Setup Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Setup STT
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                inputText.setText("Listening...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                inputText.setText("Processing Voice...")
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK -> "Network Error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network Timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    13 -> "Offline Language Pack Missing"
                    else -> "Voice Error: $error"
                }
                inputText.setText(msg)
                chatAdapter.addMessage(ChatMessage(msg, isUser = false))
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val voiceText = matches[0]

                    generateText(voiceText, isVoice = true, displayPrompt = voiceText)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        voiceButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                speechRecognizer.startListening(speechRecognizerIntent)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
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
        etSystemPrompt.setText(LLMManager.getSystemPrompt(this))
        
        btnSavePrompt.setOnClickListener {
            val newPrompt = etSystemPrompt.text.toString()
            prefs.edit().putString("custom_system_prompt", newPrompt).apply()
            LLMManager.resetConversation()
            Toast.makeText(this, "System Prompt Saved & Applied!", Toast.LENGTH_SHORT).show()
        }
        
        btnResetPrompt.setOnClickListener {
            val defaultPrompt = LLMManager.getDefaultSystemPrompt(this)
            etSystemPrompt.setText(defaultPrompt)
            prefs.edit().remove("custom_system_prompt").apply()
            LLMManager.resetConversation()
            Toast.makeText(this, "Reset to Default Prompt!", Toast.LENGTH_SHORT).show()
        }


        setupUseCases()
        
        // Custom Spinner Layouts to fix Dark Mode contrast issue
        val adapter = ArrayAdapter(this, R.layout.spinner_item, supportedModels.map { "[${it.automotiveContext}] ${it.name} (${it.size})" })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        modelSpinner.adapter = adapter
        modelSpinner.setSelection(4) // Default to Gemma 4-E2B IT
        
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentModel = supportedModels[position]
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
                    val url = java.net.URL("http://google.com")
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
        
        // Wake Word Initialization
        val currentWakeWord = prefs.getString("wake_word", "hey auto")
        etWakeWord.setText(currentWakeWord)
        
        val isWakeWordEnabled = prefs.getBoolean("wake_word_enabled", false)
        switchWakeWord.isChecked = isWakeWordEnabled
        if (isWakeWordEnabled) {
            startForegroundService(Intent(this, WakeWordService::class.java))
        }
        
        switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wake_word_enabled", isChecked).apply()
            val intent = Intent(this, WakeWordService::class.java)
            if (isChecked) {
                startForegroundService(intent)
            } else {
                stopService(intent)
            }
        }
        
        etWakeWord.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("wake_word", s.toString().lowercase()).apply()
                if (switchWakeWord.isChecked) {
                    val intent = Intent(this@LocalLLMActivity, WakeWordService::class.java)
                    startForegroundService(intent)
                }
            }
        })
    }

    private fun showTelemetrySettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_telemetry_settings, null)
        val etSpeed = dialogView.findViewById<android.widget.EditText>(R.id.etSpeed)
        val etEvBattery = dialogView.findViewById<android.widget.EditText>(R.id.etEvBattery)
        val etTirePressure = dialogView.findViewById<android.widget.EditText>(R.id.etTirePressure)
        val etOutsideTemp = dialogView.findViewById<android.widget.EditText>(R.id.etOutsideTemp)
        val etKvCache = dialogView.findViewById<android.widget.EditText>(R.id.etKvCache)

        etSpeed.setText(VehicleManager.getRealSpeed().toString())
        etEvBattery.setText(VehicleManager.getEvBatteryLevel().toString())
        etTirePressure.setText(VehicleManager.getTirePressureFrontLeft().toString())
        etOutsideTemp.setText(VehicleManager.getOutsideTemperature().toString())
        
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val currentKvCache = prefs.getInt("max_tokens", 512)
        val currentAutoFlush = prefs.getInt("auto_flush_interval", 3)
        etKvCache.setText(currentKvCache.toString())
        val etAutoFlush = dialogView.findViewById<android.widget.EditText>(R.id.etAutoFlush)
        etAutoFlush.setText(currentAutoFlush.toString())

        android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newSpeed = etSpeed.text.toString().toFloatOrNull()
                val newBattery = etEvBattery.text.toString().toFloatOrNull()
                val newTire = etTirePressure.text.toString().toFloatOrNull()
                val newTemp = etOutsideTemp.text.toString().toFloatOrNull()
                val newKvCache = etKvCache.text.toString().toIntOrNull()
                val newAutoFlush = etAutoFlush.text.toString().toIntOrNull()

                if (newSpeed != null) VehicleManager.setMockSpeed(newSpeed)
                if (newBattery != null) VehicleManager.setMockEvBatteryLevel(newBattery)
                if (newTire != null) VehicleManager.setMockTirePressure(newTire)
                if (newTemp != null) VehicleManager.setMockOutsideTemperature(newTemp)

                updateDashboardUI()
                Toast.makeText(this, "Telemetry Updated", Toast.LENGTH_SHORT).show()
                
                if (newAutoFlush != null) {
                    prefs.edit().putInt("auto_flush_interval", newAutoFlush).apply()
                }

                if (newKvCache != null && newKvCache != currentKvCache) {
                    prefs.edit().putInt("max_tokens", newKvCache).apply()
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
    }

    override fun onDestroy() {
        stopEmergencyAlarm()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        try {
            VehicleManager.cleanup()
        } catch (e: Exception) {
            Log.e("LocalLLMActivity", "Failed to cleanup VehicleManager", e)
        }
        super.onDestroy()
    }

    private fun checkModelExists() {
        val internalDir = applicationContext.filesDir
        val externalDir = applicationContext.getExternalFilesDir(null)
        val tmpDir = java.io.File("/data/local/tmp/")
        
        val internalFiles = internalDir.listFiles()?.toList() ?: emptyList()
        val externalFiles = externalDir?.listFiles()?.toList() ?: emptyList()
        val tmpFiles = tmpDir.listFiles()?.toList() ?: emptyList()
        val allFiles = internalFiles + externalFiles + tmpFiles
        
        var modelFile = allFiles.firstOrNull { it.name == currentModel.filename }
        
        // Dynamic Fallback: If exact filename is not found, use any existing .bin, .task, or .litertlm file
        if (modelFile == null) {
            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                ?: models.find { it.name.contains("Qwen", ignoreCase = true) }
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
            executeScenario("Sensors indicate the driver is falling asleep! Output EXACTLY this JSON: {\"action\": \"sound_alarm\"} and provide a short, urgent voice message to wake them up.")
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
        val useCpu = switchCpuBackend.isChecked
        
        // Save to SharedPreferences for AssistantSession (Voice Overlay)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("selected_model", MODEL_PATH)
            .putBoolean("use_cpu", useCpu)
            .apply()
            
        chatAdapter.addMessage(ChatMessage("Loading Model from $MODEL_PATH...\nBackend: ${if (useCpu) "CPU" else "GPU"}\nThis may take a minute.", isUser = false))
        generateButton.isEnabled = false

        LLMManager.initialize(applicationContext, MODEL_PATH, force, useCpu, object : LLMManager.InitCallback {
            override fun onSuccess() {
                chatAdapter.addMessage(ChatMessage("Model Loaded successfully! Ready for inference.", isUser = false))
                chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                generateButton.isEnabled = true
                btnLoadModel.isEnabled = false
            }

            override fun onError(e: Exception) {
                chatAdapter.addMessage(ChatMessage("Failed to load model from $MODEL_PATH.\nException: ${e.message}", isUser = false))
            }
        })
    }
    
    private fun triggerEmergencyAlarm() {
        if (alarmJob?.isActive == true) return
        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        alarmJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
                delay(2000)
            }
        }
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

    private fun executeToolCall(toolCall: String) {
        try {
            if (toolCall.startsWith("increaseTemperature")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 1.0
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                VehicleManager.writeTemperatureToVhal((currentTemp + value).toFloat())
            } else if (toolCall.startsWith("decreaseTemperature")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 1.0
                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                VehicleManager.writeTemperatureToVhal((currentTemp - value).toFloat())
            } else if (toolCall.startsWith("setTemperature")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                VehicleManager.writeTemperatureToVhal(value.toFloat())
            } else if (toolCall.startsWith("turnOnDefroster")) {
                VehicleManager.writeDefrosterToVhal(true)
            } else if (toolCall.startsWith("turnOffDefroster")) {
                VehicleManager.writeDefrosterToVhal(false)
            } else if (toolCall.startsWith("setSeatHeater")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 1
                VehicleManager.writeSeatHeaterToVhal(value)
            } else if (toolCall.startsWith("setSeatMassager")) {
                val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 1
                VehicleManager.writeSeatMassagerToVhal(value)
            } else if (toolCall.startsWith("setWindowPosition")) {
                if (VehicleManager.getRealSpeed() > 70) {
                    android.util.Log.w("SafetyGuardrail", "Speed > 70mph. Ignored setWindowPosition tool.")
                    chatAdapter.addMessage(ChatMessage("System: Safety lockout prevented window action at high speed.", isUser = false))
                } else {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull()?.toInt() ?: 50
                    VehicleManager.writeWindowPositionToVhal(value)
                }
            } else if (toolCall.startsWith("navigate")) {
                val dest = toolCall.substringAfter("(").substringBefore(")")
                
                // On AOSP Emulator, the Maps app silently swallows intents without throwing exceptions.
                // We bypass it by trying to explicitly launch the AOSP WebView Shell first.
                val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(dest)}"))
                browserIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                browserIntent.setPackage("org.chromium.webview_shell")
                
                try {
                    applicationContext.startActivity(browserIntent)
                } catch (e: Exception) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${android.net.Uri.encode(dest)}"))
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        applicationContext.startActivity(intent)
                    } catch (e2: Exception) {
                        val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(dest)}"))
                        fallbackIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        try {
                            applicationContext.startActivity(fallbackIntent)
                        } catch (e3: Exception) {
                            android.util.Log.e("Navigation", "Failed to launch any navigation intents", e3)
                        }
                    }
                }
            } else if (toolCall.startsWith("playMusic")) {
                val query = toolCall.substringAfter("(").substringBefore(")")
                val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                intent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                intent.putExtra(android.app.SearchManager.QUERY, query)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            } else if (toolCall.startsWith("call")) {
                val contact = toolCall.substringAfter("(").substringBefore(")")
                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${android.net.Uri.encode(contact)}"))
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } else if (toolCall.startsWith("remember")) {
                val fact = toolCall.substringAfter("(").substringBefore(")")
                val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val currentMemory = prefs.getString("user_memory", "") ?: ""
                val newMemory = if (currentMemory.isEmpty()) fact else "$currentMemory. $fact"
                prefs.edit().putString("user_memory", newMemory).apply()
            }
            updateDashboardUI()
        } catch (e: Exception) {
            android.util.Log.e("LocalLLMActivity", "Failed to parse tool call: $toolCall", e)
        }
    }

    private fun generateText(prompt: String, isVoice: Boolean = false, displayPrompt: String = "") {
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
        
        if (LLMManager.conversation == null) {
            chatAdapter.replaceLastMessage("System: Please click 'Load Model' before sending a prompt.")
            resetControls()
            return
        }
        
        if (LLMManager.isWarmingUp) {
            chatAdapter.updateLastMessage("\n\n[Warming up AI context... please wait]")
            lifecycleScope.launch {
                while (LLMManager.isWarmingUp) {
                    delay(500)
                }
                chatAdapter.replaceLastMessage("")
                processQuery(prompt, isVoice, displayPrompt)
            }
        } else {
            processQuery(prompt, isVoice, displayPrompt)
        }
    }
    
    private fun processQuery(prompt: String, isVoice: Boolean, displayPrompt: String) {
        try {
            var alarmTriggered = false
            timeoutJob?.cancel()
            timeoutJob = lifecycleScope.launch {
                delay(180000) // 3 minutes max (First-time GPU Shader Compilation can take up to 2-3 mins on AAOS)
                if (isGenerating) {
                    runOnUiThread {
                        chatAdapter.updateLastMessage("\n\n[Model Hang Detected - Restarting]")
                        resetControls()
                    }
                    initLlm(force = true)
                }
            }
            val finalPrompt = if (LLMManager.isFirstMessage) {
                LLMManager.isFirstMessage = false
                LLMManager.getSystemPrompt(applicationContext) + "\nUser: " + prompt
            } else {
                "[Current State: Temp ${VehicleManager.getRealTemperature()}F, Speed ${VehicleManager.getRealSpeed()}mph, Heater ${VehicleManager.getRealSeatHeaterLevel()}]\nUser: " + prompt
            }

            val executedTools = mutableSetOf<String>()
            val regex = "<TOOL>(.*?)</TOOL>".toRegex()
            val spokenTextLength = intArrayOf(0)

            val startTime = System.currentTimeMillis()
            var firstTokenTime = -1L

            LLMManager.conversation?.sendMessageAsync(
                com.google.ai.edge.litertlm.Contents.of(com.google.ai.edge.litertlm.Content.Text(finalPrompt)),
                object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        if (firstTokenTime == -1L) {
                            firstTokenTime = System.currentTimeMillis()
                            val ttft = firstTokenTime - startTime
                            android.util.Log.i("LLMLatency", "[LocalLLMActivity] Time to First Token (TTFT): ${ttft}ms")
                        }
                        
                        runOnUiThread {
                            if (!isGenerating) return@runOnUiThread
                            val chunk = message.toString()
                            lastResponseBuilder.append(chunk)
                            
                            val currentText = lastResponseBuilder.toString()
                            val matches = regex.findAll(currentText)
                            for (match in matches) {
                                val toolCall = match.groups[1]?.value ?: continue
                                if (executedTools.add(toolCall)) {
                                    executeToolCall(toolCall)
                                }
                            }
                            
                            val displayMsg = currentText.replace(regex, "").trim()
                            chatAdapter.replaceLastMessage(displayMsg)
                            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                            
                            if (isVoice) {
                                var remainingText = displayMsg.substring(spokenTextLength[0])
                                val sentenceRegex = "^(.*?)([.!?]+(?:\\s+|$)|\\n)".toRegex()
                                var match = sentenceRegex.find(remainingText)
                                while (match != null) {
                                    val sentence = match.value
                                    spokenTextLength[0] += sentence.length
                                    tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "PARTIAL")
                                    
                                    remainingText = displayMsg.substring(spokenTextLength[0])
                                    match = sentenceRegex.find(remainingText)
                                }
                            }
                        }
                    }
                    
                    override fun onDone() {
                        val totalTime = System.currentTimeMillis() - startTime
                        android.util.Log.i("LLMLatency", "[LocalLLMActivity] Total Generation Time: ${totalTime}ms")
                        
                        runOnUiThread {
                            if (!isGenerating) return@runOnUiThread
                            timeoutJob?.cancel()
                            
                            var finalResponse = lastResponseBuilder.toString()
                            
                            // Auto-Context Clearing Hack for silent KV Cache overflows
                            if (finalResponse.trim().length <= 3) {
                                android.util.Log.w("LocalLLMActivity", "Suspiciously short response. KV Cache full. Resetting...")
                                chatAdapter.addMessage(ChatMessage("Context Limit Exceeded. Automatically clearing history...", isUser = false))
                                LLMManager.resetConversation()
                                resetControls()
                                generateText(prompt, isVoice, displayPrompt)
                                return@runOnUiThread
                            }
                            
                            finalResponse = finalResponse.replace(regex, "").trim()
                            if (finalResponse.isEmpty() && executedTools.isNotEmpty()) {
                                finalResponse = executedTools.joinToString("\n") { tool ->
                                    when {
                                        tool.startsWith("increaseTemperature") -> "I've increased the temperature by ${tool.substringAfter("(").substringBefore(")")} degrees."
                                        tool.startsWith("decreaseTemperature") -> "I've decreased the temperature by ${tool.substringAfter("(").substringBefore(")")} degrees."
                                        tool.startsWith("setTemperature") -> "I've set the temperature to ${tool.substringAfter("(").substringBefore(")")} degrees."
                                        tool.startsWith("setSeatHeater") -> "I've adjusted the seat heater."
                                        tool.startsWith("setSeatMassager") -> "I've turned on the seat massager for you."
                                        tool.startsWith("turnOnDefroster") -> "I've turned on the defroster."
                                        tool.startsWith("turnOffDefroster") -> "I've turned off the defroster."
                                        tool.startsWith("setWindowPosition") -> "I've adjusted the windows."
                                        tool.startsWith("navigate") -> "Routing to ${tool.substringAfter("(").substringBefore(")")}."
                                        tool.startsWith("playMusic") -> "Playing ${tool.substringAfter("(").substringBefore(")")}."
                                        tool.startsWith("call") -> "Calling ${tool.substringAfter("(").substringBefore(")")}."
                                        tool.startsWith("remember") -> "Got it, I've remembered that."
                                        else -> "Action completed."
                                    }
                                }
                            }
                            chatAdapter.replaceLastMessage(finalResponse)
                            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                            
                            // Make sure dashboard reflects any VHAL changes made by the AI
                            updateDashboardUI()
                            
                            resetControls()
                            if (isVoice) {
                                val remainingSentence = finalResponse.substring(spokenTextLength[0]).trim()
                                if (remainingSentence.isNotEmpty()) {
                                    tts.speak(remainingSentence, TextToSpeech.QUEUE_ADD, null, "PARTIAL")
                                }
                                val finalUtterance = if (finalResponse.trim().endsWith("?")) "QUESTION_FINAL" else "STATEMENT_FINAL"
                                tts.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, finalUtterance)
                            }
                        }
                    }
                    
                    override fun onError(throwable: Throwable) {
                        runOnUiThread {
                            timeoutJob?.cancel()
                            val errorMsg = throwable.message ?: ""
                            chatAdapter.updateLastMessage("\nError: $errorMsg")
                            resetControls()
                            LLMManager.isFirstMessage = true
                            
                            if (errorMsg.contains("busy", ignoreCase = true) || errorMsg.contains("processing", ignoreCase = true) || errorMsg.contains("invoke", ignoreCase = true)) {
                                chatAdapter.addMessage(ChatMessage("Context Limit Exceeded. Clearing history...", isUser = false))
                                LLMManager.resetConversation()
                            }
                        }
                    }
                },
                emptyMap()
            )
        } catch (e: Exception) {
            timeoutJob?.cancel()
            val errorMsg = e.message ?: ""
            chatAdapter.updateLastMessage("\nError: $errorMsg")
            resetControls()
            LLMManager.isFirstMessage = true
            
            if (errorMsg.contains("busy", ignoreCase = true) || errorMsg.contains("processing", ignoreCase = true) || errorMsg.contains("invoke", ignoreCase = true)) {
                chatAdapter.addMessage(ChatMessage("Context Limit Exceeded. Clearing history...", isUser = false))
                LLMManager.resetConversation()
            }
        }
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
                        LLMManager.getSystemPrompt(applicationContext) + "\nUser: " + query
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
}

package com.example.gemininano

import android.Manifest
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
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
    private lateinit var tabInference: View
    private lateinit var tabUseCases: View
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
    private lateinit var stopButton: Button
    private lateinit var clearButton: Button

    private var generationFuture: com.google.common.util.concurrent.ListenableFuture<String>? = null
    
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
    
    // Default to Gemma (Index 4) because SmolLM has a corrupted tokenizer in MediaPipe
    private var currentModel = supportedModels[4] 

    private var MODEL_PATH = ""
    private var isGenerating = false
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var alarmJob: Job? = null
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        VehicleManager.initialize(this)
        
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        generateButton = findViewById(R.id.generateButton)
        voiceButton = findViewById(R.id.voiceButton)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        tabLayout = findViewById(R.id.tabLayout)
        tabInference = findViewById(R.id.tabInference)
        tabUseCases = findViewById(R.id.tabUseCases)
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
        stopButton = findViewById(R.id.stopButton)
        clearButton = findViewById(R.id.clearButton)

        // Re-use test logic when intent action matches
        if (intent.action == "com.example.gemininano.RUN_TESTS" && !isTestRunning) {
            isTestRunning = true
            android.util.Log.i("AutomatedTest", "Starting automated test suite...")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runAutomatedTests()
                isTestRunning = false
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
                inputText.setText("")
                chatAdapter.addMessage(ChatMessage("Voice Error: $error", isUser = false))
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val voiceText = matches[0]

                    val fullPrompt = buildSystemPrompt(voiceText)
                    generateText(fullPrompt, isVoice = true, displayPrompt = voiceText)
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
                if (tab?.position == 0) {
                    tabInference.visibility = View.VISIBLE
                    tabUseCases.visibility = View.GONE
                } else {
                    tabInference.visibility = View.GONE
                    tabUseCases.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        setupUseCases()
        
        // Custom Spinner Layouts to fix Dark Mode contrast issue
        val adapter = ArrayAdapter(this, R.layout.spinner_item, supportedModels.map { "[${it.automotiveContext}] ${it.name} (${it.size})" })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        modelSpinner.adapter = adapter
        
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
                val fullPrompt = buildSystemPrompt(prompt)
                generateText(fullPrompt, displayPrompt = prompt)
            }
        }

        stopButton.setOnClickListener {
            generationFuture?.cancel(true)
            chatAdapter.updateLastMessage("\n\n[Generation Stopped]")
            resetControls()
            lifecycleScope.launch { initLlm(force = true) }
        }

        clearButton.setOnClickListener {
            if (isGenerating) {
                generationFuture?.cancel(true)
                lifecycleScope.launch { initLlm(force = true) }
            }
            chatAdapter.clearMessages()
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
    


    private fun buildSystemPrompt(userInput: String): String {
        return "You are a concise in-car AI assistant.\n" +
               "Current state: Speed ${VehicleManager.getRealSpeed()}mph, Cabin Temp ${VehicleManager.getRealTemperature()}F, Heater ${VehicleManager.getRealSeatHeaterLevel()}, Fuel Level ${VehicleManager.getFuelLevel()}%, Gear ${VehicleManager.getGearSelection()}.\n" +
               "RULES:\n" +
               "1. You must respond in valid JSON format ONLY. Do not include extra text.\n" +
               "2. If user asks to change temp, output: {\"action\": \"set_temperature\", \"value\": 75, \"message\": \"[confirmation]\"} (replace 75 with requested temp)\n" +
               "3. If user asks to defrost, output: {\"action\": \"defrost\", \"status\": true, \"message\": \"[confirmation]\"}\n" +
               "4. For all other queries, output: {\"action\": \"chat\", \"message\": \"[your answer]\"}\n" +
               "5. If Gear is Drive, refuse any distracting requests for safety.\n" +
               "User: '$userInput'\n" +
               "Assistant:"
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
            if (LLMManager.llmInference == null) {
                chatAdapter.addMessage(ChatMessage("System: Please load a model first before executing a scenario.", isUser = false))
                chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            } else if (!isGenerating) {
                val fullPrompt = buildSystemPrompt(prompt)
                generateText(fullPrompt, displayPrompt = prompt)
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
        chatAdapter.addMessage(ChatMessage("Loading Model from $MODEL_PATH... This may take a minute.", isUser = false))
        generateButton.isEnabled = false

        LLMManager.initialize(applicationContext, MODEL_PATH, force, object : LLMManager.InitCallback {
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
        
        try {
            var alarmTriggered = false
            val timeoutJob = lifecycleScope.launch {
                delay(45000) // 45 seconds max
                if (isGenerating) {
                    runOnUiThread {
                        chatAdapter.updateLastMessage("\n\n[Model Hang Detected - Restarting]")
                        resetControls()
                    }
                    initLlm(force = true)
                }
            }
            generationFuture = LLMManager.llmInference?.generateResponseAsync(prompt) { partialResult, done ->
                runOnUiThread {
                    var cleanResult = partialResult
                    if (cleanResult.contains("<end_of_turn>")) cleanResult = cleanResult.replace("<end_of_turn>", "")
                    if (cleanResult.contains("<eos>")) cleanResult = cleanResult.replace("<eos>", "")
                    if (cleanResult.contains("<turn|>")) cleanResult = cleanResult.replace("<turn|>", "")
                    if (cleanResult.contains("<|im_end|>")) cleanResult = cleanResult.replace("<|im_end|>", "")
                    if (cleanResult.contains("im_end")) cleanResult = cleanResult.replace("im_end", "")
                    if (cleanResult.contains("<|im_start|>")) cleanResult = cleanResult.replace("<|im_start|>", "")
                    
                    lastResponseBuilder.append(cleanResult)
                    
                    val currentText = lastResponseBuilder.toString()
                    var forceDone = false
                    
                    val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(currentText)
                    if (jsonMatch != null) {
                        try {
                            val jsonString = jsonMatch.value
                            val json = org.json.JSONObject(jsonString)
                            val action = json.optString("action")
                            if (action == "set_temperature") {
                                val temp = json.optDouble("value", VehicleManager.getRealTemperature().toDouble())
                                VehicleManager.writeTemperatureToVhal(temp.toFloat())
                                updateDashboardUI()
                            } else if (action == "increase_temperature") {
                                val amount = json.optDouble("value", 2.0)
                                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                                VehicleManager.writeTemperatureToVhal((currentTemp + amount).toFloat())
                                updateDashboardUI()
                            } else if (action == "decrease_temperature") {
                                val amount = json.optDouble("value", 2.0)
                                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                                VehicleManager.writeTemperatureToVhal((currentTemp - amount).toFloat())
                                updateDashboardUI()
                            } else if (action == "defrost") {
                                VehicleManager.writeDefrosterToVhal(json.optBoolean("status", true))
                            }
                            
                            val displayMsg = json.optString("message", "Done.")
                            lastResponseBuilder.clear()
                            lastResponseBuilder.append(displayMsg)
                            chatAdapter.replaceLastMessage(displayMsg)
                            
                            forceDone = true
                            
                            // Forcefully terminate background generation
                            try {
                                val inference = LLMManager.llmInference!!
                                val implicitSessionField = inference.javaClass.getDeclaredField("implicitSession")
                                implicitSessionField.isAccessible = true
                                val sessionRef = implicitSessionField.get(inference) as java.util.concurrent.atomic.AtomicReference<*>
                                val session = sessionRef.get()
                                if (session != null) {
                                    val cancelMethod = session.javaClass.getDeclaredMethod("cancelGenerateResponseAsync")
                                    cancelMethod.isAccessible = true
                                    cancelMethod.invoke(session)
                                }
                            } catch (e: Exception) {}
                        } catch (e: Exception) {
                            // JSON not fully formed
                        }
                    }
                    
                    if (!forceDone && !done) {
                        val msgIndex = currentText.indexOf("\"message\": \"")
                        if (msgIndex != -1) {
                            var extracted = currentText.substring(msgIndex + 12)
                            val endQuoteIndex = extracted.indexOf("\"")
                            if (endQuoteIndex != -1) {
                                extracted = extracted.substring(0, endQuoteIndex)
                            }
                            chatAdapter.replaceLastMessage(extracted)
                        } else {
                            chatAdapter.replaceLastMessage("Thinking...")
                        }
                    } else if (done && !forceDone) {
                        chatAdapter.replaceLastMessage("Sorry, I didn't understand that.")
                    }
                    
                    chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    
                    if (!alarmTriggered && (lastResponseBuilder.contains("sound_alarm") || prompt.contains("falling asleep"))) {
                        alarmTriggered = true
                        triggerEmergencyAlarm()
                    }
                    
                    if (done || forceDone) {
                        timeoutJob.cancel()
                        resetControls()
                        if (isVoice) {
                            tts.speak(lastResponseBuilder.toString(), TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                        if (forceDone) return@runOnUiThread
                    }
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            chatAdapter.updateLastMessage("\nError: $errorMsg")
            resetControls()
            if (errorMsg.contains("busy", ignoreCase = true) || errorMsg.contains("processing", ignoreCase = true) || errorMsg.contains("invocation", ignoreCase = true)) {
                chatAdapter.addMessage(ChatMessage("Restarting busy model...", isUser = false))
                lifecycleScope.launch { initLlm(force = true) }
            }
        }
    }

    private suspend fun runAutomatedTests() {
        android.util.Log.i("AutomatedTest", "Initializing tests...")
        while (MODEL_PATH.isEmpty()) {
            kotlinx.coroutines.delay(100)
        }
        
        if (LLMManager.llmInference == null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    LLMManager.initialize(this@LocalLLMActivity, MODEL_PATH)
                    android.util.Log.i("AutomatedTest", "Model loaded successfully.")
                } catch (e: Exception) {
                    android.util.Log.e("AutomatedTest", "Failed to load model: ${e.message}")
                }
            }
        }
        
        val prompts = listOf(
            "increase temp by 5 degrees", "decrease temp by 10%", "set temperature to 74F",
            "turn on the heater", "it's too cold in here", "defrost the windshield",
            "can you make it warmer", "set temp to 68 degrees", "reduce temperature by 3 degrees",
            "turn the AC down", "make the cabin 72 degrees", "increase heat to maximum",
            "I'm freezing", "I'm boiling", "drop temp by 5 degrees", "crank up the heat",
            "lower temp by 12 percent", "set passenger temp to 70", "change temperature to 75",
            "defrost front window", "defrost rear window", "turn off defroster", "warm up the car",
            "cool down the car", "set temp to 22 celsius", "increase temperature", "decrease temperature",
            "make it hot", "make it cold", "set AC to 65", "increase AC by 2 degrees",
            "temperature up", "temperature down", "temp up", "temp down", "warm me up",
            "cool me down", "set climate to 70", "climate control 68", "adjust temp to 71",
            "modify temperature to 69", "change cabin climate to 73", "turn heat up by 5",
            "turn AC down by 5", "set heat to 80", "set AC to 60", "increase temperature to 75F",
            "decrease temperature to 65F", "set temp 70", "temp 72", "heat 80", "cool 65",
            "defrost", "clear the windshield", "remove fog from window", "defog", "defrost on",
            "defrost off", "make it warmer by 10%", "make it cooler by 10%", "increase temp by 20%",
            "set temp to max", "set temp to min", "turn up the temperature", "turn down the temperature",
            "raise temperature", "lower temperature", "boost heat", "reduce heat", "increase AC",
            "decrease AC", "set internal temp to 74", "cabin temp 70", "car temp 68",
            "make temperature 72", "put temp at 71", "change temp to 69", "adjust heat to 75",
            "set cooling to 65", "increase cabin temp", "decrease cabin temp", "raise cabin temp",
            "lower cabin temp", "turn up cabin temp", "turn down cabin temp", "set temperature 70",
            "set temperature 75", "set temperature 80", "set temperature 60", "set temperature 65",
            "set temperature 68", "set temperature 72", "set temperature 74", "set temperature 71",
            "set temperature 73", "set temperature 69", "set temperature 67", "set temperature 66",
            "set temperature 76", "set temperature 77"
        )
        
        var passed = 0
        val resultsBuilder = java.lang.StringBuilder()
        resultsBuilder.append("# Automated LLM Prompt Test Results\n\n")
        resultsBuilder.append("| Prompt | Status | Action | JSON Output |\n")
        resultsBuilder.append("|---|---|---|---|\n")

        val modelName = LLMManager.currentModelPath.lowercase()
        val isGemma = modelName.contains("gemma")
        
        for (query in prompts) {
            val systemPrompt = if (isGemma) {
                "You are an in-car AI. Current Temp: ${VehicleManager.getRealTemperature()}F, Speed: ${VehicleManager.getRealSpeed()}mph, Fuel: ${VehicleManager.getFuelLevel()}%.\n" +
                "You must ONLY output valid JSON.\n" +
                "Example 1:\nUser: 'increase temp by 5 degrees' -> {\"action\": \"increase_temperature\", \"value\": 5, \"message\": \"Increasing temperature by 5 degrees.\"}\n" +
                "Example 2:\nUser: 'set temperature to 74F' -> {\"action\": \"set_temperature\", \"value\": 74, \"message\": \"Setting temperature to 74 degrees.\"}\n" +
                "Example 3:\nUser: 'decrease temp by 10%' -> {\"action\": \"decrease_temperature\", \"value\": ${(VehicleManager.getRealTemperature() * 0.1).toInt()}, \"message\": \"Decreasing temperature by 10%.\"}\n" +
                "Example 4:\nUser: 'defrost windshield' -> {\"action\": \"defrost\", \"status\": true, \"message\": \"Defroster on.\"}\n" +
                "Example 5:\nUser: 'hello' -> {\"action\": \"chat\", \"message\": \"Hi there!\"}\n\nUser: '$query' -> {"
            } else {
                "You are an in-car AI. Output ONLY JSON.\n" +
                "User: 'increase temp' -> {\"action\": \"increase_temperature\", \"value\": 2, \"message\": \"Done\"}\n" +
                "User: 'defrost' -> {\"action\": \"defrost\", \"status\": true, \"message\": \"Done\"}\n" +
                "User: '$query' -> {"
            }
                   
            var status = "FAIL"
            var details = "Timeout/Error"
            var jsonOut = "N/A"
            
            val latch = java.util.concurrent.CountDownLatch(1)
            val lastResponseBuilder = java.lang.StringBuilder()
            lastResponseBuilder.append("{") // Seed with the forced bracket
            
            try {
                LLMManager.llmInference?.generateResponseAsync(systemPrompt) { partialResult, done ->
                    android.util.Log.d("AutomatedTest", "Callback fired. done=$done, partialResult='$partialResult'")
                    var cleanResult = partialResult
                    if (cleanResult.contains("<end_of_turn>")) cleanResult = cleanResult.replace("<end_of_turn>", "")
                    if (cleanResult.contains("<eos>")) cleanResult = cleanResult.replace("<eos>", "")
                    if (cleanResult.contains("<turn|>")) cleanResult = cleanResult.replace("<turn|>", "")
                    if (cleanResult.contains("<|im_end|>")) cleanResult = cleanResult.replace("<|im_end|>", "")
                    if (cleanResult.contains("im_end")) cleanResult = cleanResult.replace("im_end", "")
                    if (cleanResult.contains("<|im_start|>")) cleanResult = cleanResult.replace("<|im_start|>", "")
                    
                    lastResponseBuilder.append(cleanResult)
                    val fullResponse = lastResponseBuilder.toString()
                    
                    if (latch.count > 0 && fullResponse.contains("{") && fullResponse.contains("}")) {
                        try {
                            val jsonString = fullResponse.substringAfter("{").substringBeforeLast("}")
                            val json = org.json.JSONObject("{$jsonString}")
                            val action = json.optString("action")
                            details = action
                            jsonOut = "{$jsonString}"
                            android.util.Log.i("AutomatedTest", "Parsed JSON: $jsonOut")
                            
                            if (action == "set_temperature") {
                                val temp = json.optDouble("value", VehicleManager.getRealTemperature().toDouble())
                                VehicleManager.writeTemperatureToVhal(temp.toFloat())
                                details = "set_temp($temp)"
                            } else if (action == "increase_temperature") {
                                val amount = json.optDouble("value", 2.0)
                                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                                VehicleManager.writeTemperatureToVhal((currentTemp + amount).toFloat())
                                details = "inc_temp($amount)"
                            } else if (action == "decrease_temperature") {
                                val amount = json.optDouble("value", 2.0)
                                val currentTemp = VehicleManager.getRealTemperature().toDouble()
                                VehicleManager.writeTemperatureToVhal((currentTemp - amount).toFloat())
                                details = "dec_temp($amount)"
                            } else if (action == "defrost") {
                                VehicleManager.writeDefrosterToVhal(json.optBoolean("status", true))
                            }
                            
                            if (action == "set_temperature" || action == "increase_temperature" || action == "decrease_temperature" || action == "defrost" || action == "chat") {
                                status = "PASS"
                                passed++
                            }
                            
                            try {
                                val inference = LLMManager.llmInference!!
                                val implicitSessionField = inference.javaClass.getDeclaredField("implicitSession")
                                implicitSessionField.isAccessible = true
                                val sessionRef = implicitSessionField.get(inference) as java.util.concurrent.atomic.AtomicReference<*>
                                val session = sessionRef.get()
                                if (session != null) {
                                    val cancelMethod = session.javaClass.getDeclaredMethod("cancelGenerateResponseAsync")
                                    cancelMethod.isAccessible = true
                                    cancelMethod.invoke(session)
                                }
                            } catch (e: Exception) {
                                // Ignore reflection errors in tests
                            }
                            
                            latch.countDown()
                        } catch (e: Exception) {
                            // JSON not fully formed yet, continue building
                        }
                    } else if (done && latch.count > 0) {
                        details = "Invalid JSON or Model Timeout"
                        jsonOut = fullResponse
                        latch.countDown()
                    }
                }
                
                val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    status = "FAIL"
                    details = "Timeout (60s)"
                }
            } catch (e: IllegalStateException) {
                status = "FAIL"
                details = "Model Busy / Crashed"
                android.util.Log.e("AutomatedTest", "Skipping remaining tests due to busy model.")
                break
            }
            android.util.Log.i("AutomatedTest", "Query: '$query' -> Status: $status ($details). Output: ${if (status == "FAIL") lastResponseBuilder.toString() else ""}")
            resultsBuilder.append("| $query | $status | $details | $jsonOut |\n")
        }
        
        val reportHeader = "Total Tests: ${prompts.size}\nPass: $passed\nFail: ${prompts.size - passed}\n\n"
        val finalReport = reportHeader + resultsBuilder.toString()
        
        try {
            val file = java.io.File(getExternalFilesDir(null), "test_results.md")
            file.writeText(finalReport)
            android.util.Log.i("AutomatedTest", "Test complete. Results written to ${file.absolutePath}")
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

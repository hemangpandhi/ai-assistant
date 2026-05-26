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

    data class MockVehicleState(
        var speed: Int = 65,
        var temperature: Int = 72,
        var seatHeaterLevel: Int = 0,
        var fuelLevelPercent: Int = 45,
        var isMediaPlaying: Boolean = false,
        var ambientLighting: String = "White",
        var windowsOpen: Boolean = false
    )
    private val vehicleState = MockVehicleState()

    data class LlmModel(val name: String, val filename: String, val url: String, val size: String, val automotiveContext: String)
    private val supportedModels = listOf(
        LlmModel("SmolLM 135M Instruct", "SmolLM-135M-Instruct.task", "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task", "150MB", "Entry-Level"),
        LlmModel("TinyLlama-1.1B", "TinyLlama-1.1B-Chat-v1.0.task", "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task", "1.1GB", "Entry-Level"),
        LlmModel("Qwen2.5 1.5B Instruct", "Qwen2.5-1.5B-Instruct.litertlm", "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", "1.6GB", "Mid-Range"),
        LlmModel("Gemma 2B INT4", "gemma-2b-it-gpu-int4.bin", "", "1.8GB", "Mid-Range"),
        LlmModel("Gemma 4-E2B IT", "gemma-4-E2B-it.litertlm", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "2.5GB", "Mid-Range"),
        LlmModel("Phi-4-mini", "Phi-4-mini-instruct.litertlm", "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm", "3.8GB", "Premium")
    )
    
    // Default to SmolLM (Index 0) to guarantee fast startup and fallback if Gemma isn't downloaded.
    private var currentModel = supportedModels[0] 

    private var MODEL_PATH = ""
    private var isGenerating = false
    private var lastResponseBuilder = java.lang.StringBuilder()
    private var alarmJob: Job? = null
    
    private var carPropertyManager: CarPropertyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            val car = Car.createCar(this)
            carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        } catch (e: Exception) {
            Log.e("LocalLLMActivity", "Failed to initialize CarPropertyManager", e)
        }
        
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

        supportActionBar?.title = "MediaPipe Local LLM"
        
        chatAdapter = ChatAdapter(mutableListOf())
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter

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
        }

        clearButton.setOnClickListener {
            chatAdapter.clearMessages()
            resetControls()
        }
    }

    private fun updateDashboardUI() {
        val dashTemp = findViewById<android.widget.TextView>(R.id.dashTemp)
        val dashSpeed = findViewById<android.widget.TextView>(R.id.dashSpeed)
        val dashHeater = findViewById<android.widget.TextView>(R.id.dashHeater)
        
        runOnUiThread {
            dashTemp?.text = "🌡️ Temp: ${getRealTemperature()}°F"
            dashSpeed?.text = "🏎️ Speed: ${vehicleState.speed}mph"
            dashHeater?.text = "💺 Heater: ${if (vehicleState.seatHeaterLevel > 0) "Level ${vehicleState.seatHeaterLevel}" else "Off"}"
        }
    }
    
    private fun getRealTemperature(): Int {
        return try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            val currentTemp = carPropertyManager?.getFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId) ?: vehicleState.temperature.toFloat()
            currentTemp.toInt()
        } catch (e: Exception) {
            Log.e("LocalLLMActivity", "Failed to read VHAL temp", e)
            vehicleState.temperature
        }
    }

    private fun setRealTemperature(temp: Float) {
        try {
            val config = carPropertyManager?.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
            val areaId = config?.areaIds?.firstOrNull() ?: 0
            carPropertyManager?.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, areaId, temp)
        } catch (e: Exception) {
            Log.e("LocalLLMActivity", "Failed to write VHAL temp", e)
        }
    }

    private fun buildSystemPrompt(userInput: String): String {
        return "You are an in-car Android Automotive assistant. " +
               "Current state: Speed ${vehicleState.speed}mph, " +
               "Cabin Temp ${getRealTemperature()}F, " +
               "Seat Heater Level ${vehicleState.seatHeaterLevel}. " +
               "If the user asks to increase temperature, output exactly <TEMP_UP>. If they ask to decrease it, output <TEMP_DOWN>. " +
               "The user says: '$userInput'."
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
            modelFile = allFiles.firstOrNull { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
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

    private suspend fun initLlm() = withContext(Dispatchers.Main) {
        chatAdapter.addMessage(ChatMessage("Loading Model from $MODEL_PATH... This may take a minute.", isUser = false))
        generateButton.isEnabled = false

        LLMManager.initialize(applicationContext, MODEL_PATH, object : LLMManager.InitCallback {
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
                    
                    var didUpdateTemp = false
                    if (lastResponseBuilder.contains("<TEMP_UP>")) {
                        vehicleState.temperature = getRealTemperature() + 4
                        setRealTemperature(vehicleState.temperature.toFloat())
                        didUpdateTemp = true
                        val idx = lastResponseBuilder.indexOf("<TEMP_UP>")
                        lastResponseBuilder.delete(idx, idx + 9)
                    }
                    if (lastResponseBuilder.contains("<TEMP_DOWN>")) {
                        vehicleState.temperature = getRealTemperature() - 4
                        setRealTemperature(vehicleState.temperature.toFloat())
                        didUpdateTemp = true
                        val idx = lastResponseBuilder.indexOf("<TEMP_DOWN>")
                        lastResponseBuilder.delete(idx, idx + 11)
                    }
                    
                    if (didUpdateTemp) {
                        updateDashboardUI()
                    }
                    
                    chatAdapter.replaceLastMessage(lastResponseBuilder.toString())
                    chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    
                    // SmolLM (135M) frequently hallucinates JSON. 
                    // For demo reliability, we also trigger if the original prompt was the emergency scenario.
                    if (!alarmTriggered && (lastResponseBuilder.contains("sound_alarm") || prompt.contains("falling asleep"))) {
                        alarmTriggered = true
                        triggerEmergencyAlarm()
                    }
                    
                    if (done) {
                        resetControls()
                        if (isVoice) {
                            tts.speak(lastResponseBuilder.toString(), TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            chatAdapter.updateLastMessage("\nError: ${e.message}")
            resetControls()
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

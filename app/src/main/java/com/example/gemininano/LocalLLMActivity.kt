package com.example.gemininano

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.mediapipe.tasks.genai.llminference.LlmInference

class LocalLLMActivity : AppCompatActivity() {
    private lateinit var inputText: EditText
    private lateinit var generateButton: Button
    private lateinit var outputText: TextView
    private lateinit var btnUseCase1: Button
    private lateinit var btnUseCase2: Button
    private lateinit var btnUseCase3: Button
    private lateinit var btnDownloadModel: Button
    private lateinit var progressBar: android.widget.ProgressBar

    private var llmInference: LlmInference? = null
    // SELinux blocks apps from reading /data/local/tmp/ directly.
    // The safest location is the app's own internal storage directory.
    private val MODEL_PATH = "/data/data/com.example.gemininano/files/gemma-4-E2B-it.litertlm"
    private val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        generateButton = findViewById(R.id.generateButton)
        outputText = findViewById(R.id.outputText)
        btnUseCase1 = findViewById(R.id.btnUseCase1)
        btnUseCase2 = findViewById(R.id.btnUseCase2)
        btnUseCase3 = findViewById(R.id.btnUseCase3)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        progressBar = findViewById(R.id.progressBar)

        supportActionBar?.title = "MediaPipe Local LLM"

        setupUseCases()

        // Initialize the local model in the background
        val modelFile = java.io.File(MODEL_PATH)
        if (modelFile.exists() && modelFile.length() > 0) {
            lifecycleScope.launch {
                initLlm()
            }
        } else {
            outputText.text = "Model not found at \$MODEL_PATH.\nPlease click 'Download Model' to fetch it."
            generateButton.isEnabled = false
        }

        btnDownloadModel.setOnClickListener {
            downloadModel()
        }

        generateButton.setOnClickListener {
            val prompt = inputText.text.toString()
            if (prompt.isNotEmpty()) {
                generateText(prompt)
            }
        }
    }

    private fun setupUseCases() {
        btnUseCase1.setOnClickListener {
            inputText.setText("Summarize this traffic report: Heavy congestion on I-5 North due to a 3-car collision near exit 12. Delays expected up to 45 minutes. Alternate route via SR-99 is highly recommended.")
        }
        btnUseCase2.setOnClickListener {
            inputText.setText("Generate a polite, 1-sentence reply to this SMS while I am driving: 'Hey, are we still meeting at the restaurant at 7? I might be 10 minutes late.'")
        }
        btnUseCase3.setOnClickListener {
            inputText.setText("Explain this car diagnostic error to a driver in simple terms: OBD-II Code P0171 (System Too Lean Bank 1).")
        }
    }

    private suspend fun initLlm() = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                outputText.text = "Loading Model from \$MODEL_PATH... This may take a minute."
                generateButton.isEnabled = false
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(MODEL_PATH)
                .setMaxTokens(1024)
                .build()
            
            llmInference = LlmInference.createFromOptions(applicationContext, options)
            Log.d("LocalLLM", "LLM Initialized")
            
            withContext(Dispatchers.Main) {
                outputText.text = "Model Loaded successfully! Ready for inference."
                generateButton.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e("LocalLLM", "Error initializing model", e)
            val stackTrace = android.util.Log.getStackTraceString(e)
            withContext(Dispatchers.Main) {
                outputText.text = "Failed to load model from \$MODEL_PATH.\n\nException: \${e.javaClass.simpleName}\nMessage: \${e.message}\n\nStack Trace:\n\$stackTrace"
            }
        }
    }

    private fun generateText(prompt: String) {
        outputText.text = "Generating response..."
        generateButton.isEnabled = false
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // In a real application, you'd want to format the prompt according to 
                // the model's instruction tuning template (e.g. <start_of_turn>user...)
                val response = llmInference?.generateResponse(prompt)
                
                withContext(Dispatchers.Main) {
                    outputText.text = response ?: "Error: Empty response"
                    generateButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputText.text = "Error during generation: \${e.message}"
                    generateButton.isEnabled = true
                }
            }
        }
    }

    private fun downloadModel() {
        btnDownloadModel.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        outputText.text = "Starting download (2.5GB)... Please keep the app open."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(MODEL_URL)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connect()

                // Use contentLengthLong because 2.5GB exceeds the 32-bit Integer limit
                val fileLength = connection.contentLengthLong
                val input = java.io.BufferedInputStream(url.openStream())
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
                                outputText.text = "Downloading... \$progress% (\${total / 1024 / 1024} MB / \${fileLength / 1024 / 1024} MB)"
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
                    outputText.text = "Download complete! Initializing model..."
                }

                initLlm()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = android.view.View.GONE
                    btnDownloadModel.isEnabled = true
                    val stackTrace = android.util.Log.getStackTraceString(e)
                    outputText.text = "Download failed: \${e.message}\n\$stackTrace"
                }
            }
        }
    }
}

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
    private lateinit var btnLoadModel: Button
    private lateinit var progressBar: android.widget.ProgressBar

    private var llmInference: LlmInference? = null
    // The safest location is the app's own internal storage directory.
    private var MODEL_PATH = ""
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
        btnLoadModel = findViewById(R.id.btnLoadModel)
        progressBar = findViewById(R.id.progressBar)

        supportActionBar?.title = "MediaPipe Local LLM"

        setupUseCases()

        // Check if model exists dynamically in internal or external storage
        val internalDir = applicationContext.filesDir
        val externalDir = applicationContext.getExternalFilesDir(null)
        
        val internalFiles = internalDir.listFiles()?.toList() ?: emptyList()
        val externalFiles = externalDir?.listFiles()?.toList() ?: emptyList()
        val allFiles = internalFiles + externalFiles
        
        val modelFile = allFiles.firstOrNull {
            it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm")
        }

        if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
            MODEL_PATH = modelFile.absolutePath
            outputText.text = "Model found locally:\n\${modelFile.name}\n\nClick 'Load Local Model' to initialize."
            generateButton.isEnabled = false
            btnLoadModel.isEnabled = true
        } else {
            val allNames = allFiles.map { it.name }.joinToString(", ").takeIf { it.isNotEmpty() } ?: "empty"
            MODEL_PATH = java.io.File(externalDir ?: internalDir, "gemma-4-E2B-it.litertlm").absolutePath
            outputText.text = "No model found.\nChecked: \${externalDir?.absolutePath}\nContents: \$allNames\n\nPlease click 'Download' or push via ADB."
            generateButton.isEnabled = false
            btnLoadModel.isEnabled = false
        }

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
                var finalUrl = MODEL_URL
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

                // Use contentLengthLong because 2.5GB exceeds the 32-bit Integer limit
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

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

    private var llmInference: LlmInference? = null
    // SELinux blocks apps from reading /data/local/tmp/ directly.
    // The safest location is the app's own internal storage directory.
    private val MODEL_PATH = "/data/data/com.example.gemininano/files/gemma-2b-it-gpu-int4.bin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        generateButton = findViewById(R.id.generateButton)
        outputText = findViewById(R.id.outputText)
        btnUseCase1 = findViewById(R.id.btnUseCase1)
        btnUseCase2 = findViewById(R.id.btnUseCase2)
        btnUseCase3 = findViewById(R.id.btnUseCase3)

        supportActionBar?.title = "MediaPipe Local LLM"

        setupUseCases()

        // Initialize the local model in the background
        lifecycleScope.launch {
            initLlm()
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
            withContext(Dispatchers.Main) {
                outputText.text = "Failed to load model from \$MODEL_PATH.\\nError: \${e.message}\\n\\nPlease ensure you have pushed the model via ADB to /data/local/tmp/"
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
}

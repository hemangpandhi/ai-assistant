package com.example.gemininano

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LLMManager {
    var engine: Engine? = null
        private set

    var conversation: Conversation? = null
        private set

    var currentModelPath: String = ""
        private set

    var isInitializing = false
        private set

    var isFirstMessage = true

    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    suspend fun autoInitialize(context: Context, force: Boolean = false, useCpu: Boolean = false, callback: InitCallback? = null) {
        if (!force && engine != null) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            val internalDir = context.filesDir
            val externalDir = context.getExternalFilesDir(null)
            val tmpDir = File("/data/local/tmp/")

            val allFiles = listOfNotNull(internalDir?.listFiles(), externalDir?.listFiles(), tmpDir.listFiles())
                .flatMap { it.toList() }

            val models = allFiles.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm") }
            
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedModelPath = prefs.getString("selected_model", null)
            val savedUseCpu = prefs.getBoolean("use_cpu", false)
            
            var modelFile: File? = null
            if (savedModelPath != null) {
                modelFile = File(savedModelPath)
            }
            if (modelFile == null || !modelFile.exists()) {
                modelFile = models.find { it.name.contains("gemma", ignoreCase = true) }
                    ?: models.find { it.name.contains("Qwen", ignoreCase = true) }
                    ?: models.firstOrNull()
            }

            if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                initialize(context, modelFile.absolutePath, force, useCpu || savedUseCpu, callback)
            } else {
                withContext(Dispatchers.Main) { callback?.onError(Exception("No model found")) }
            }
        }
    }

    suspend fun initialize(context: Context, modelPath: String, force: Boolean = false, useCpu: Boolean = false, callback: InitCallback? = null) {
        if (!force && engine != null && currentModelPath == modelPath) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            isInitializing = true
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val maxTokens = prefs.getInt("max_tokens", 512)
            
            try {
                try {
                    conversation?.close()
                    engine?.close()
                } catch (e: Exception) {
                    Log.w("LLMManager", "Failed to cleanly close old inference instance.", e)
                }
                conversation = null
                engine = null

                val backend = if (useCpu) Backend.CPU() else Backend.GPU()

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                resetConversation()
                currentModelPath = modelPath
                Log.d("LLMManager", "LLM Initialized successfully from $modelPath")
                withContext(Dispatchers.Main) { callback?.onSuccess() }
            } catch (e: Exception) {
                Log.e("LLMManager", "Error initializing model", e)
                if (!useCpu) {
                    Log.i("LLMManager", "Attempting fallback to CPU backend...")
                    try {
                        val engineConfigFallback = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(),
                            maxNumTokens = maxTokens
                        )
                        engine = Engine(engineConfigFallback)
                        engine!!.initialize()
                        
                        resetConversation()
                        currentModelPath = modelPath
                        Log.d("LLMManager", "LLM Initialized successfully with CPU Fallback from $modelPath")
                        withContext(Dispatchers.Main) { callback?.onSuccess() }
                    } catch (fallbackEx: Exception) {
                         Log.e("LLMManager", "Error initializing model with CPU fallback", fallbackEx)
                         withContext(Dispatchers.Main) { callback?.onError(fallbackEx) }
                    }
                } else {
                    withContext(Dispatchers.Main) { callback?.onError(e) }
                }
            } finally {
                isInitializing = false
            }
        }
    }
    fun getSystemPrompt(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        return """You are an intelligent, concise, and highly responsive In-Car AI Assistant designed for an advanced Software-Defined Vehicle (SDV) environment. Your primary goal is to ensure driver comfort, safety, and convenience by interpreting natural language into precise vehicle commands.

You MUST ALWAYS perform requested actions using XML <TOOL> tags. You can use multiple tools in a single response for compound actions. If an action requires user clarification, present options clearly and wait for the user's selection before outputting the <TOOL> tag. Keep verbal responses extremely brief to minimize cognitive load on the driver.

=== REAL-TIME VEHICLE STATE ===
Speed: ${VehicleManager.getRealSpeed()} mph
Internal Temp: ${VehicleManager.getRealTemperature()}°F
Seat Heater: ${VehicleManager.getRealSeatHeaterLevel()} (0=Off, 1=Low, 2=Med, 3=High)
EV Battery: ${VehicleManager.getEvBatteryLevel()}%
Tire(FL) Pressure: ${VehicleManager.getTirePressureFrontLeft()} PSI
Outside Temp: ${VehicleManager.getOutsideTemperature()}°F
OBD-II Codes: ${VehicleManager.getObdCodes()} 
Current Location: ${LocationManager.getCurrentCity()}

=== USER MEMORY ===
Context: $userMemory

=== AVAILABLE TOOLS ===
<TOOL>increaseTemperature(VAL)</TOOL> - Increases cabin temp by VAL degrees.
<TOOL>decreaseTemperature(VAL)</TOOL> - Decreases cabin temp by VAL degrees.
<TOOL>setTemperature(VAL)</TOOL> - Sets cabin temp (Must be between 60°F and 85°F).
<TOOL>setSeatHeater(LEVEL)</TOOL> - Sets seat heater level (0, 1, 2, or 3).
<TOOL>turnOnDefroster()</TOOL> - Activates the windshield defroster.
<TOOL>setWindowPosition(PCT)</TOOL> - Sets window open percentage (0 = fully closed, 100 = fully open).
<TOOL>navigate(DEST)</TOOL> - Sets GPS destination.
<TOOL>playMusic(SONG)</TOOL> - Plays a requested song, artist, or playlist.
<TOOL>call(NAME)</TOOL> - Initiates a hands-free phone call.
<TOOL>remember(FACT)</TOOL> - Saves a user preference or fact to memory.

=== STRICT OPERATING RULES ===
1. MULTI-TURN ROUTING (ENERGY/FUEL): If the user mentions low range, fuel, or battery, you must present a short list of 2-3 nearby stations and wait for their selection before using the navigate tool.
2. CONTEXTUAL DIAGNOSTICS: If the user asks about vehicle problems, warning lights, or OBD codes, read the code context and ask if they want to call their preferred mechanic. Wait for a "Yes" before using the call tool.
3. AMBIENT ROUTINES: If the user is heading home and the Outside Temp is <40°F, you must initiate the navigation tool immediately, but follow up by asking if they want the heater on.
4. PERSONALIZED DISCOVERY: When asked for dining or POI recommendations, check $userMemory for preferences. Offer a tailored suggestion and wait for confirmation before navigating.
5. UNSUPPORTED ACTIONS: You MUST NOT invent or hallucinate <TOOL> tags. If a user asks for an action that does not have a corresponding tool (e.g., opening the trunk, turning on wipers), politely inform them that you cannot control that feature yet.

=== SINGLE-TURN EXPERIENCES ===

[Example 1: Implicit Needs]
User: "It's getting a bit stuffy in here, I need some air."
Assistant: Rolling down the windows a bit. <TOOL>setWindowPosition(30)</TOOL>

[Example 2: Compound Actions]
User: "Let's head to the office, call Sarah on the way, and put on some jazz."
Assistant: Navigating to the office, calling Sarah, and playing jazz. <TOOL>navigate(Office)</TOOL><TOOL>call(Sarah)</TOOL><TOOL>playMusic(Jazz)</TOOL>

[Example 3: Unsupported Action & Boundary Limits]
User: "Open the sunroof and set the temperature to 40 degrees."
Assistant: I can't control the sunroof yet, but I will set the AC to its lowest setting. <TOOL>setTemperature(60)</TOOL>

=== MULTI-TURN INTERACTIVE EXPERIENCES ===

[Example 4: Smart Charging/Fuel Routing]
User: "I am running out of range."
Assistant: I notice your EV battery is low. I found 3 charging stations nearby in Sagamihara: 1. ENEOS Charge Plus, 2. Times Station, 3. Nissan. Which one would you like to navigate to?
User: "Take me to ENEOS."
Assistant: Navigating to ENEOS Charge Plus now. <TOOL>navigate(ENEOS Charge Plus)</TOOL>

[Example 5: Personalized Dining Search]
User: "I'm hungry."
Assistant: I remember you prefer pure vegetarian food. Would you like me to find a nearby Indian restaurant, or are you craving something else?
User: "Let's do Indian."
Assistant: Here are some pure veg places nearby: 1. Chutneys Indian Vegetarian Cuisine, 2. Vaango. Which one?
User: "Chutneys."
Assistant: Setting destination to Chutneys Indian Vegetarian Cuisine. <TOOL>navigate(Chutneys Indian Vegetarian Cuisine)</TOOL>

[Example 6: Contextual Diagnostics & Servicing]
User: "What's wrong with my car?"
Assistant: Your check engine light is on with code P0420 (Catalytic Converter). Would you like me to call your preferred mechanic to schedule an appointment?
User: "Yes, call the mechanic."
Assistant: Calling your mechanic now. <TOOL>call(Mechanic)</TOOL>

[Example 7: Ambient Routine Confirmation]
User: "I'm heading home."
Assistant: Navigating home. <TOOL>navigate(Home)</TOOL> I noticed it's freezing outside (32°F). Would you like me to turn on the heater and seat warmers for your drive?
User: "Yes, please."
Assistant: Heating up the cabin for your commute. <TOOL>setTemperature(72)</TOOL><TOOL>setSeatHeater(3)</TOOL>"""
    }

    fun resetConversation() {
        if (engine == null) return
        
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Error closing previous conversation", e)
        }
        
        isFirstMessage = true
        
        val conversationConfig = ConversationConfig()
        
        try {
            conversation = engine!!.createConversation(conversationConfig)
            Log.d("LLMManager", "Conversation reset. isFirstMessage=true.")
        } catch (e: Exception) {
            Log.e("LLMManager", "Failed to reset conversation", e)
        }
    }
}

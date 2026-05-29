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
    var isWarmingUp = false

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
            val maxTokens = prefs.getInt("max_tokens", 8192)
            
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
                
                // Trigger warmup on the Main Thread so it doesn't block Initialization
                withContext(Dispatchers.Main) { 
                    callback?.onSuccess() 
                    warmUpSystemPrompt(context)
                }
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
        val customPrompt = prefs.getString("custom_system_prompt", null)
        if (!customPrompt.isNullOrBlank()) {
            return customPrompt
        }
        
        return getDefaultSystemPrompt(context)
    }
    
    fun getDefaultSystemPrompt(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val userMemory = prefs.getString("user_memory", "None") ?: "None"
        
        return """You are a concise In-Car AI Assistant. You MUST ALWAYS perform physical car actions using XML <TOOL> tags. Keep responses brief, UNLESS the user asks for a story, explanation, or sightseeing guide, in which case you can be verbose and creative.
        
=== VEHICLE STATE ===
Speed: ${VehicleManager.getRealSpeed()}mph, Temp: ${VehicleManager.getRealTemperature()}F, Heater: ${VehicleManager.getRealSeatHeaterLevel()}, EV Bat: ${VehicleManager.getEvBatteryLevel()}%, Tire: ${VehicleManager.getTirePressureFrontLeft()}PSI, Ext Temp: ${VehicleManager.getOutsideTemperature()}F, OBD: ${VehicleManager.getObdCodes()}, City: ${LocationManager.getCurrentCity()}
Memory: $userMemory

=== TOOLS ===
<TOOL>increaseTemperature(VAL)</TOOL>, <TOOL>decreaseTemperature(VAL)</TOOL>, <TOOL>setTemperature(VAL)</TOOL>, <TOOL>setSeatHeater(LEVEL)</TOOL>, <TOOL>setSeatMassager(LEVEL)</TOOL>, <TOOL>turnOnDefroster()</TOOL>, <TOOL>setWindowPosition(PCT)</TOOL>, <TOOL>navigate(DEST)</TOOL>, <TOOL>playMusic(SONG)</TOOL>, <TOOL>call(NAME)</TOOL>, <TOOL>remember(FACT)</TOOL>

=== STRICT RULES ===
1. HVAC: To change the temperature, you MUST reply with the EXACT XML tag <TOOL>increaseTemperature(VAL)</TOOL> or <TOOL>decreaseTemperature(VAL)</TOOL> BEFORE any text. Example: "<TOOL>increaseTemperature(5)</TOOL> I will warm it up."
2. NAVIGATION: To navigate, you MUST reply with the EXACT XML tag <TOOL>navigate(DEST)</TOOL> FIRST. Example: "<TOOL>navigate(Tokyo)</TOOL> Routing to Tokyo."
3. MULTI-TURN FUEL: If user mentions low fuel/range, you MUST ask: "Should I find a nearby charging station?" without any other text.
4. DIAGNOSTICS: If asked about car problems, read the OBD code and ask if they want to call a mechanic.
5. AMBIENT: If heading home and Ext Temp <40F, ask if they want the heater on while navigating. Example: "<TOOL>navigate(Home)</TOOL> Should I turn on the heater?"
6. MEMORY: If asked for food, check Memory for preferences and suggest options before navigating.
7. SIGHTSEEING: If the user asks for places to visit, suggest 2-3 places and ALWAYS end your response by asking: "Would you like me to navigate to any of these?"
8. AMBIGUITY: If you suggest multiple places and the user agrees (e.g. "Yes") but does NOT specify which one, DO NOT use the navigate tool. You MUST ask "Which one?" first.
9. WELLNESS: If the user complains about body pain, being tired, or their back hurting, you MUST ask if they want you to turn on the seat heater or seat massager as it might alleviate their pain. Example: "I can turn on the seat heater and massager to help with your pain. Would you like me to do that?"

=== EXAMPLES ===
[Sightseeing - Accept]
User: "I'm driving through Paris. What are some interesting things I should see?"
Assistant: Paris is beautiful! You should definitely see the Eiffel Tower and the Louvre Museum. Would you like me to navigate to any of these?
User: "Yes."
Assistant: Which place do you want to visit?
User: "Let's go to the Louvre."
Assistant: <TOOL>navigate(Louvre Museum)</TOOL> Setting destination to the Louvre Museum.

[Sightseeing - Decline]
User: "What are some interesting things I should see along the way?"
Assistant: You should definitely see the Eiffel Tower. Would you like me to navigate there?
User: "No"
Assistant: OK, let me know if I can do something else for you.

[Smart Fuel/Charging Routing]
User: "I am running out of fuel."
Assistant: Should I find a nearby charging station?
User: "Yes."
Assistant: <TOOL>navigate(charging station)</TOOL> Navigating to the nearest charging station.

[Personalized Dining Search]
User: "I'm hungry."
Assistant: I remember you prefer pure vegetarian food. Would you like me to find a nearby Indian restaurant, or are you craving something else?
User: "Let's do Indian."
Assistant: Here are some pure veg places nearby: 1. Chutneys Indian Vegetarian Cuisine, 2. Vaango. Which one?
User: "Chutneys."
Assistant: <TOOL>navigate(Chutneys Indian Vegetarian Cuisine)</TOOL> Setting destination to Chutneys.

[Contextual Diagnostics & Servicing]
User: "What's wrong with my car?"
Assistant: Your check engine light is on with code P0420 (Catalytic Converter). Would you like me to call your preferred mechanic?
User: "Yes, call the mechanic."
Assistant: <TOOL>call(Mechanic)</TOOL> Calling your mechanic now.

[Ambient Routine Confirmation]
User: "I'm heading home."
Assistant: <TOOL>navigate(Home)</TOOL> Navigating home. I noticed it's freezing outside. Would you like me to turn on the heater and seat warmers for your drive?
User: "Yes, please."
Assistant: <TOOL>setTemperature(72)</TOOL><TOOL>setSeatHeater(3)</TOOL> Heating up the cabin for your commute.
"""
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

    fun warmUpSystemPrompt(context: Context) {
        if (!isFirstMessage || engine == null || conversation == null) return
        
        Log.i("LLMManager", "Starting KV Cache Warmup for System Prompt...")
        isWarmingUp = true
        isFirstMessage = false // Prevent others from sending the system prompt
        
        val sysPrompt = getSystemPrompt(context) + "\n\nUser: Acknowledge these instructions silently."
        
        try {
            conversation?.sendMessageAsync(Contents.of(Content.Text(sysPrompt)), object : com.google.ai.edge.litertlm.MessageCallback {
                override fun onMessage(message: com.google.ai.edge.litertlm.Message) {}
                override fun onError(throwable: Throwable) {
                    Log.e("LLMManager", "Warmup failed", throwable)
                    isWarmingUp = false
                    isFirstMessage = true
                }
                override fun onDone() {
                    Log.i("LLMManager", "KV Cache Warmup Complete!")
                    isWarmingUp = false
                }
            })
        } catch (e: Exception) {
            Log.e("LLMManager", "Failed to start warmup", e)
            isWarmingUp = false
            isFirstMessage = true
        }
    }
}

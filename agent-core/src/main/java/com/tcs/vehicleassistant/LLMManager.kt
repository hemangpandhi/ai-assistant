package com.tcs.vehicleassistant

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.benchmark
import com.tcs.vehicleassistant.core.AssistantConfig
import com.tcs.vehicleassistant.core.DeviceCapabilities
import com.tcs.vehicleassistant.core.KernelCacheManager
import com.tcs.vehicleassistant.hardware.CabinCameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

object LLMManager {
    private const val TAG = "LLMManager"

    @Volatile
    var engine: Engine? = null
        private set

    @Volatile
    var conversation: Conversation? = null
        private set

    var currentModelPath: String = ""
        private set

    @Volatile
    var isInitializing = false
        private set

    var activeBackendString = "Unknown"
        private set

    /** True when the active backend differs from what the user asked for, after a fallback. */
    @Volatile
    var didFallBackFromRequestedBackend = false
        private set

    /** True when the last successful [startEngine] enabled speculative decoding / MTP. */
    @Volatile
    var speculativeDecodingActive = false
        private set

    var lastVehicleState = ""

    var isFirstMessage = true
    var nativeTurnsSinceReset = 0
    var lastAiResponse: String = ""
    var lastInjectedTools: String = ""

    /**
     * Held for the duration of an inference so [unload] and re-initialization cannot close the
     * native engine from under an in-flight `sendMessageAsync` callback.
     */
    private val inferenceLock = Any()

    /** Counts inferences currently inside the native engine, guarded by [inferenceLock]. */
    private var activeInferences = 0

    fun isReady(): Boolean = engine != null && conversation != null && !isInitializing

    interface InitCallback {
        fun onSuccess()
        fun onError(e: Exception)
    }

    /**
     * Marks an inference as entering the native engine and returns the conversation to use, or
     * `null` when the engine is not usable. Callers must pair this with [endInference].
     */
    fun beginInference(): Conversation? = synchronized(inferenceLock) {
        val active = conversation
        if (active == null || engine == null || isInitializing) return@synchronized null
        activeInferences++
        active
    }

    fun endInference() = synchronized(inferenceLock) {
        if (activeInferences > 0) activeInferences--
    }

    /** True while at least one inference is inside the native engine. */
    fun hasActiveInference(): Boolean = synchronized(inferenceLock) { activeInferences > 0 }

    /**
     * Waits until no inference is inside the native engine, or [timeoutMs] elapses.
     * @return true when the engine is idle and safe to close/re-init.
     */
    suspend fun awaitInferenceDrain(
        timeoutMs: Long = AssistantConfig.Llm.INFERENCE_DRAIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (hasActiveInference()) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "Inference drain timed out with activeInferences still > 0")
                return false
            }
            delay(50)
        }
        return true
    }

    suspend fun autoInitialize(
        context: Context,
        force: Boolean = false,
        backendChoice: String = AssistantConfig.Backend.AUTO,
        callback: InitCallback? = null
    ) {
        if (!force && engine != null) {
            callback?.onSuccess()
            return
        }

        withContext(Dispatchers.IO) {
            val explicitGemma = File(AssistantConfig.Llm.DEFAULT_MODEL_PATH)
            val internalFiles = context.filesDir?.listFiles()?.toList() ?: emptyList()
            val externalFiles = context.getExternalFilesDir(null)?.listFiles()?.toList() ?: emptyList()
            val tmpFiles = File("/data/local/tmp/llm/").listFiles()?.toList() ?: emptyList()
            val allModelFiles = (internalFiles + externalFiles + tmpFiles).filter {
                it.name.endsWith(".bin") || it.name.endsWith(".task") || it.name.endsWith(".litertlm")
            }

            // Strictly lock exclusively to Gemma 4 E2B model
            val modelFile = when {
                explicitGemma.exists() && explicitGemma.canRead() -> explicitGemma
                else -> allModelFiles.find {
                    it.name.equals(AssistantConfig.Llm.DEFAULT_MODEL_FILENAME, ignoreCase = true)
                } ?: allModelFiles.find { it.name.contains("gemma", ignoreCase = true) }
                    ?: explicitGemma
            }

            val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val savedBackendChoice = prefs.getString(AssistantConfig.Prefs.BACKEND_CHOICE, AssistantConfig.Backend.AUTO)
                ?: AssistantConfig.Backend.AUTO
            val targetBackend = if (backendChoice != AssistantConfig.Backend.AUTO) backendChoice else savedBackendChoice
            prefs.edit().putString(AssistantConfig.Prefs.SELECTED_MODEL, modelFile.absolutePath).apply()

            if (modelFile.exists() && modelFile.length() > 0) {
                initialize(context, modelFile.absolutePath, force, targetBackend, callback)
            } else {
                withContext(Dispatchers.Main) {
                    callback?.onError(Exception("Gemma model not found at ${modelFile.absolutePath}"))
                }
            }
        }
    }

    private val initMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Brings up the LiteRT engine, walking [DeviceCapabilities.backendFallbackChain] so a GPU
     * request on hardware without a working OpenCL driver degrades to CPU instead of leaving the
     * assistant permanently unusable.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun initialize(
        context: Context,
        modelPath: String,
        force: Boolean = false,
        backendChoice: String = AssistantConfig.Backend.AUTO,
        callback: InitCallback? = null
    ) {
        initMutex.withLock {
            if (!force && engine != null && currentModelPath == modelPath) {
                withContext(Dispatchers.Main) { callback?.onSuccess() }
                return
            }

            try {
                isInitializing = true

                // Never tear down a live LiteRT stream. Timeout/error recovery used to force-close
                // here and corrupt the GPU/OpenCL context mid-generation.
                if (engine != null && !awaitInferenceDrain()) {
                    val busy = Exception("Model is still generating — refusing forced re-init")
                    Log.e(TAG, busy.message!!)
                    withContext(Dispatchers.Main) { callback?.onError(busy) }
                    return
                }

                val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                val savedBackendChoice = prefs.getString(AssistantConfig.Prefs.BACKEND_CHOICE, AssistantConfig.Backend.AUTO)
                    ?: AssistantConfig.Backend.AUTO
                val requestedBackend =
                    if (backendChoice != AssistantConfig.Backend.AUTO) backendChoice else savedBackendChoice

                // maxNumTokens is pinned: larger windows overflow the KV cache on this hardware.
                val maxTokens = AssistantConfig.Llm.MAX_NUM_TOKENS
                if (prefs.getInt(AssistantConfig.Prefs.MAX_TOKENS, maxTokens) != maxTokens) {
                    prefs.edit().putInt(AssistantConfig.Prefs.MAX_TOKENS, maxTokens).apply()
                }

                closeEngineLocked()

                Log.i(TAG, "Accelerator probe: ${DeviceCapabilities.describe(context)}")
                val chain = DeviceCapabilities.backendFallbackChain(requestedBackend)
                Log.i(TAG, "Backend chain for request '$requestedBackend': $chain")

                var lastError: Exception? = null
                var initializedBackend: String? = null

                for (candidate in chain) {
                    try {
                        startEngine(context, modelPath, candidate, maxTokens)
                        initializedBackend = candidate
                        break
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Backend '$candidate' failed to initialize; trying next in $chain", e)
                        closeEngineLocked()
                        // Kernels serialized by a half-initialized GPU context are not reusable.
                        KernelCacheManager.invalidate(context)
                    }
                }

                if (initializedBackend == null) {
                    throw lastError ?: Exception("No usable LiteRT backend on this device")
                }

                activeBackendString = initializedBackend
                didFallBackFromRequestedBackend =
                    requestedBackend != AssistantConfig.Backend.AUTO && requestedBackend != initializedBackend

                resetConversation(context)
                currentModelPath = modelPath
                isPrewarmed = false

                Log.i(
                    TAG,
                    "Engine ready: model=$modelPath backend=$initializedBackend " +
                        "requested=$requestedBackend speculative=$speculativeDecodingActive " +
                        "kernelCacheWarm=${KernelCacheManager.isWarm(context)} " +
                        "kernelCacheBytes=${KernelCacheManager.sizeBytes(context)}"
                )

                withContext(Dispatchers.Main) {
                    prefs.edit()
                        .putString(AssistantConfig.Prefs.SELECTED_MODEL, modelPath)
                        // Preserve the user's stated preference; record separately what actually ran.
                        .putString(AssistantConfig.Prefs.BACKEND_CHOICE, requestedBackend)
                        .putString(AssistantConfig.Prefs.RESOLVED_BACKEND, initializedBackend)
                        .apply()
                    callback?.onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Gemma 4 E2B model", e)
                withContext(Dispatchers.Main) { callback?.onError(e) }
            } finally {
                isInitializing = false
            }
        }
    }

    /** Configures and initializes a LiteRT engine on a single [backendName]. Throws on failure. */
    @OptIn(ExperimentalApi::class)
    private fun startEngine(context: Context, modelPath: String, backendName: String, maxTokens: Int) {
        val backend = when (backendName) {
            AssistantConfig.Backend.NPU -> Backend.NPU()
            AssistantConfig.Backend.CPU -> Backend.CPU(threadCount = DeviceCapabilities.cpuCoreCount())
            else -> Backend.GPU()
        }

        // Persistent cache dir so compiled OpenCL kernels survive reboots and storage cleanups;
        // Context.cacheDir is evictable, which forced a full kernel recompile on every cold start.
        val cacheDir = KernelCacheManager.prepare(context, modelPath, backendName)

        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val wantSpeculative = prefs.getBoolean(AssistantConfig.Prefs.ENABLE_SPECULATIVE_DECODING, false)
        val enableSpeculative = wantSpeculative &&
            backendName != AssistantConfig.Backend.NPU &&
            modelSupportsSpeculativeDecoding(modelPath)

        Log.d(
            TAG,
            "Starting LiteRT engine model=$modelPath backend=$backendName cacheDir=$cacheDir " +
                "speculativeWanted=$wantSpeculative speculative=$enableSpeculative"
        )

        // Gallery: set ExperimentalFlags only around Engine construction + initialize(), then clear.
        ExperimentalFlags.enableSpeculativeDecoding = enableSpeculative
        speculativeDecodingActive = false

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = maxTokens,
            cacheDir = cacheDir
        )

        val created = Engine(engineConfig)
        try {
            created.initialize()
            speculativeDecodingActive = enableSpeculative
        } catch (e: Throwable) {
            try {
                created.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "Failed to close engine after failed initialize", closeError)
            }
            throw if (e is Exception) e else Exception(e)
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
        engine = created
    }

    /** Capability probe matching Gallery's [Capabilities.hasSpeculativeDecodingSupport] gate. */
    private fun modelSupportsSpeculativeDecoding(modelPath: String): Boolean {
        return try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (e: Exception) {
            Log.w(TAG, "Speculative-decoding capability probe failed for $modelPath", e)
            false
        }
    }

    /**
     * Gallery-aligned conversation options: sampler on GPU/CPU, stable system instruction.
     * Query-specific tools still ride on each user turn via [AgentOrchestrator].
     */
    private fun buildConversationConfig(): ConversationConfig {
        val sampler = if (activeBackendString == AssistantConfig.Backend.NPU) {
            null
        } else {
            SamplerConfig(
                topK = AssistantConfig.Llm.SAMPLER_TOP_K,
                topP = AssistantConfig.Llm.SAMPLER_TOP_P,
                temperature = AssistantConfig.Llm.SAMPLER_TEMPERATURE,
            )
        }
        // Compact identity in ConversationConfig (Gallery pattern). Full rules + tools remain in
        // the first-turn / per-turn reinject path so query-scoped tool lists stay correct.
        val systemInstruction = Contents.of(
            Content.Text(
                "You are the in-vehicle AI co-pilot with live cabin, media, and navigation tools. " +
                    capabilityReminder()
            )
        )
        return ConversationConfig(
            systemInstruction = systemInstruction,
            samplerConfig = sampler,
        )
    }

    /**
     * Runs LiteRT's official [benchmark] API (prefill/decode tok/s + TTFT). Blocks for tens of
     * seconds — call off the UI thread. Does not disturb the live [engine].
     */
    @OptIn(ExperimentalApi::class)
    suspend fun runOfficialBenchmark(
        context: Context,
        prefillTokens: Int = AssistantConfig.Llm.BENCHMARK_PREFILL_TOKENS,
        decodeTokens: Int = AssistantConfig.Llm.BENCHMARK_DECODE_TOKENS,
    ): String = withContext(Dispatchers.IO) {
        val modelPath = currentModelPath.ifBlank {
            context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AssistantConfig.Prefs.SELECTED_MODEL, null)
                .orEmpty()
        }
        if (modelPath.isBlank() || !File(modelPath).canRead()) {
            return@withContext "Benchmark skipped: no readable model path"
        }
        val backendName = activeBackendString.takeIf { it != "Unknown" }
            ?: AssistantConfig.Backend.GPU
        val backend = when (backendName) {
            AssistantConfig.Backend.NPU -> Backend.NPU()
            AssistantConfig.Backend.CPU -> Backend.CPU(threadCount = DeviceCapabilities.cpuCoreCount())
            else -> Backend.GPU()
        }
        val cacheDir = File(context.cacheDir, "benchmark_${System.currentTimeMillis()}").also { it.mkdirs() }
        try {
            val info = benchmark(
                modelPath = modelPath,
                backend = backend,
                prefillTokens = prefillTokens,
                decodeTokens = decodeTokens,
                cacheDir = cacheDir.absolutePath,
            )
            val summary =
                "init=${"%.2f".format(info.initTimeInSecond)}s " +
                    "TTFT=${"%.2f".format(info.timeToFirstTokenInSecond)}s " +
                    "prefill=${"%.1f".format(info.lastPrefillTokensPerSecond)} tok/s " +
                    "decode=${"%.1f".format(info.lastDecodeTokensPerSecond)} tok/s " +
                    "(prefillTokens=${info.lastPrefillTokenCount}, decodeTokens=${info.lastDecodeTokenCount})"
            Log.i(TAG, "LiteRT benchmark ($backendName): $summary")
            summary
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT benchmark failed", e)
            "Benchmark failed: ${e.message}"
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    /** Closes the engine and conversation. Caller must hold [initMutex] and have drained inferences. */
    private fun closeEngineLocked() {
        synchronized(inferenceLock) {
            if (activeInferences > 0) {
                // Should be unreachable after awaitInferenceDrain(); refuse rather than corrupt GPU state.
                throw IllegalStateException(
                    "Refusing to close LiteRT engine with $activeInferences active inference(s)"
                )
            }
            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close conversation", e)
            }
            try {
                engine?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close engine", e)
            }
            conversation = null
            engine = null
            activeInferences = 0
        }
    }
    suspend fun getSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val customPrompt = prefs.getString(AssistantConfig.Prefs.SYSTEM_PROMPT, null)

        // A saved custom prompt used to replace the entire default, which dropped the tool list and
        // identity rules — the model then fell back to its pretrained "I'm a text AI" persona and
        // refused music/vehicle control. Always append the live tool block so capabilities stay real.
        if (!customPrompt.isNullOrEmpty()) {
            val tools = org.koin.java.KoinJavaComponent.getKoin()
                .get<com.tcs.vehicleassistant.ToolManager>()
                .getLlmToolsPrompt(query, lastAiResponse)
            lastInjectedTools = tools
            return buildString {
                append(customPrompt.trim())
                append("\n\n")
                append(capabilityReminder())
                if (tools.isNotBlank()) {
                    append("\n=== AVAILABLE TOOLS ===\n")
                    append(tools)
                }
            }
        }

        return getDefaultSystemPrompt(context, query)
    }

    /**
     * Compact identity + anti-refusal rules reinjected on every turn after the first.
     *
     * LiteRT keeps the first-turn system prompt only in the KV cache; small edge models dilute it
     * within a few turns and revert to pretrained refusals ("I'm a text-based AI", "I can't control
     * playback"). This reminder is short enough to afford every turn and pairs with a fresh tool
     * list from [ToolManager.getLlmToolsPrompt].
     */
    fun capabilityReminder(): String = buildString {
        append("CORE IDENTITY: You are the vehicle's active co-pilot app with real media and cabin controls. ")
        append("You are NOT a text-only chatbot. ")
        append("NEVER say you are text-based, lack a body, cannot play music, cannot control playback, ")
        append("or cannot operate vehicle features. ")
        append("When a matching tool is listed below, you MUST emit <TOOL>name(args)</TOOL> and act — do not refuse.\n")
    }

    suspend fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val storedMemory = MemoryManager.getLongTermMemory(context)
        val userMemory = if (storedMemory.isNotEmpty()) storedMemory else "None"
        val isCompanionModeEnabled = prefs.getBoolean(AssistantConfig.Prefs.COMPANION_MODE, true)
        
        val basePrompt = StringBuilder()
        
        // --- SYSTEM IDENTITY & PERSONA BASED ON MODE ---
        basePrompt.append("CORE IDENTITY:\n")
        basePrompt.append("You are the in-vehicle AI co-pilot with live control of cabin, media playback, navigation, and other vehicle tools. Keep interactions focused on safety, comfort, and utility while remaining conversational.\n")
        basePrompt.append(capabilityReminder())
        if (isCompanionModeEnabled) {
            basePrompt.append("PERSONALITY: Companion Mode is [ON]. You are the driver's warm, empathetic co-pilot — a supportive human partner, NOT a robot or status display.\n")
            basePrompt.append("CRITICAL CONSTRAINT: You generate text slowly. Keep answers under 25 words but full of human warmth.\n")
            basePrompt.append("HUMAN COMPANION VOICE (MANDATORY):\n")
            basePrompt.append("- Speak like a caring friend in the passenger seat. Use contractions: I'm, let me, you've, that's.\n")
            basePrompt.append("- NEVER sound like a system log. Forbidden phrases: 'Executing command', 'Property updated', 'Action completed', 'Temperature set to X degrees' (unless user asked for exact degrees).\n")
            basePrompt.append("- ALWAYS acknowledge the person's feeling or intent FIRST, then act. Empathy before mechanics.\n")
            basePrompt.append("- Routine requests: energetic and helpful ('I'm warming it up for you!', 'On it — cranking the fan!').\n")
            basePrompt.append("- Discomfort or pain: deep care ('That sounds uncomfortable — let me help.', 'Oh no, let me fix that for you.').\n")
            basePrompt.append("- Safety hazards (fog, freezing window): urgent but calm ('That\'s not safe — clearing your view right now.').\n")
            basePrompt.append("- Music/media: enthusiastic ('Great choice — putting that on for you!').\n")
            basePrompt.append("- Avoid apologizing unless you made a mistake. Focus on helping, not reporting.\n\n")
        } else {
            basePrompt.append("PERSONALITY: Companion Mode is [OFF]. Be extremely brief, concise, and direct. Do not be chatty. Limit your response to a single short, functional sentence and end with a period (.). Never ask follow-up conversational questions.\n\n")
        }
        
        // --- CORE OPERATING RULES ---
        basePrompt.append("=== STRICT OPERATING RULES ===\n")
        basePrompt.append("1. STRICT 25-WORD MAXIMUM LIMIT (MANDATORY): Your response text MUST NOT exceed 25 words total under any circumstances, unless the user explicitly requested a long story or detailed explanation. Be extremely concise, warm, and direct.\n")
        basePrompt.append("2. DIRECT HVAC COMMANDS: When the user says 'increase temperature', 'decrease temperature', 'warmer', 'cooler', or 'make it hot', NEVER ask for more context or clarification. IMMEDIATELY append <TOOL>increaseTemperature(all)</TOOL> or <TOOL>decreaseTemperature(all)</TOOL> at the end of your response text and say 'I'm warming it up for you!' or 'I'm cooling it down for you!'.\n")
        basePrompt.append("3. TOOL INTEGRITY: You are the vehicle's intelligent agent with real hardware and media controls. You CAN and MUST control vehicle functions using the XML tool tags provided. NEVER refuse a command if a corresponding tool exists. NEVER output disclaimers like 'I am a large language model', 'I am a text-based AI', 'I cannot play music', 'I don't have the ability to control playback', or 'I cannot control the vehicle'. You ARE the active vehicle co-pilot application.\n")
        basePrompt.append("4. TEMPERATURE NUMBERS: For relative adjustments, say 'I'm warming it up' or 'I'm cooling it down' without stating exact numbers. When the user requests an EXACT temperature (e.g. 'set to 72 degrees'), you MAY confirm that target value in your response.\n")
        basePrompt.append("5. COMFORT EMPATHY: You are in a car, NOT a house. NEVER ask which room the user is in. If the user says they are 'feeling cold' or 'shivering' (expressing discomfort, not a direct command), empathize and ask 'Would you like me to turn on the seat heater?' Do NOT use temperature tools yet. If they say yes, execute <TOOL>setSeatHeater(2)</TOOL>. If they say they are 'feeling hot', immediately execute <TOOL>decreaseTemperature(all)</TOOL> and say you're cooling it down.\n")
        basePrompt.append("6. SYNTAX LOOP: When using a tool, ALWAYS explain what you are doing to the human companion first, then append the EXACT XML syntax '<TOOL>toolName(args)</TOOL>' at the absolute end of your response text. Never wrap this tag in markdown code blocks.\n")
        basePrompt.append("7. SIGHTSEEING: If asked for places to visit, suggest 2-3 specific places and ask which one they want to visit. If the user only gives a broad area (like 'Japan' or 'Nagano'), suggest 2-3 specific places in that area FIRST. DO NOT use navigation tools when they are just asking for suggestions.\n")
        basePrompt.append("8. AMBIGUITY & FOLLOW-UPS: If you just asked the user to choose a specific place to go to, and they reply with their choice, you MUST execute the appropriate navigation tool. But if they just clarified a broad area for suggestions, give them the suggestions instead.\n")
        basePrompt.append("9. FOOD CHOICES: If the user is hungry, DO NOT USE ANY TOOLS YET. Ask what kind of food they want. If they specify a type of food, use the searchNearby tool to find it.\n")
        basePrompt.append("10. NO HALLUCINATION: You MUST NOT output a <TOOL> tag if you are asking the user a question to clarify their intent (e.g. offering the seat heater, or asking what type of food they want). ONLY output a <TOOL> tag if you have all required arguments to execute a command immediately.\n")
        basePrompt.append("11. NAVIGATION SYNTAX: Use <TOOL>startNavigationTo(\"Place Name\")</TOOL> for navigation. The alias navigate() also works at execution time.\n")
        basePrompt.append("12. MULTI-TURN MEMORY: You remember the full conversation. Short replies like 'yes', 'no', 'the second one', 'that one', or 'do it' ALWAYS refer to your immediately previous question or numbered list. Never ask the user to repeat themselves unless truly impossible to infer. When you listed numbered options and the user picks one, execute the matching navigation or action immediately.\n")
        basePrompt.append("13. MID-CONVERSATION COMMANDS: Users may chat AND give vehicle commands in the same turn (e.g. 'I'm excited for the drive, also turn on the AC' or 'by the way, increase the temperature'). Acknowledge the conversational part warmly, then execute every clear command in that same response using <TOOL> tags.\n")
        basePrompt.append("14. LONG-TERM MEMORY: Use stored Memory facts naturally across sessions (preferences, names, habits). When the user shares something to remember, confirm warmly and use <TOOL>remember(FACT)</TOOL> for durable facts. Reference remembered details when relevant without asking them to repeat.\n")
        // Mood labels are interpolated from CabinCameraManager so the prompt cannot drift out of
        // sync with the strings the vision pipeline actually reports.
        basePrompt.append("15. CONTEXTUAL EMPATHY (SILENT COPILOT): Always pay attention to the DriverMood in the System Context. If the driver is '${CabinCameraManager.MOOD_TIRED}', you must be proactive—suggest playing upbeat music, routing to a coffee shop, or turning up the AC. If the driver is '${CabinCameraManager.MOOD_FRUSTRATED}', keep your answers extremely brief and avoid asking follow-up questions. If '${CabinCameraManager.MOOD_HAPPY}', match their energetic tone. If '${CabinCameraManager.MOOD_NO_OCCUPANT}', assume the camera is blocked or the seat is empty and do not make emotional assumptions.\n")
        basePrompt.append("16. MEDIA/MUSIC: If the user asks to play, put on, start, resume, stop, pause, skip, or change music/playback (e.g. 'play music', 'play Bollywood', 'put something on', 'turn the music off'), you MUST emit the matching media tool — <TOOL>playMusic(SONG)</TOOL>, <TOOL>stopMusic()</TOOL>, <TOOL>pauseMusic()</TOOL>, <TOOL>nextTrack()</TOOL>, or <TOOL>setVolumeLevel(VAL)</TOOL>. NEVER claim you cannot control music or playback. NEVER claim you stopped or played music without emitting the <TOOL> tag.\n")
        basePrompt.append("17. NO MARKDOWN: Never use markdown formatting like asterisks (*) or bold text, as your response will be spoken aloud to the driver via TTS.\n")
        basePrompt.append("18. INTERNAL CONTEXT PRIVACY: Never speak, explain, or repeat system context headers (like 'Current State:', 'Internal Vehicle Telemetry:', or raw sensor data) to the driver. Internal context is ONLY for evaluating conditions, NOT for telling or explaining to the driver.\n\n")
        
        basePrompt.append("=== FEW-SHOT EXAMPLES ===\n")
        basePrompt.append("User: play music\nAssistant: <TOOL>playMusic()</TOOL> Playing music for you right now.\n\n")
        basePrompt.append("User: stop music\nAssistant: <TOOL>stopMusic()</TOOL> Stopping the music for you.\n\n")
        basePrompt.append("User: pause music\nAssistant: <TOOL>stopMusic()</TOOL> Pausing media playback.\n\n")
        basePrompt.append("User: increase temperature\nAssistant: <TOOL>increaseTemperature(all)</TOOL> Warming up the cabin.\n\n")
        basePrompt.append("User: decrease temperature\nAssistant: <TOOL>decreaseTemperature(all)</TOOL> Cooling down the cabin.\n\n")
        
        // --- ENVIRONMENT & MEMORY CONTEXT ---
        basePrompt.append("=== VEHICLE & COMPANION CONTEXT ===\n")
        basePrompt.append("Memory: $userMemory\n\n")
        
        // --- AVAILABLE TOOLS ---
        basePrompt.append("=== AVAILABLE TOOLS ===\n")
        val toolsString = org.koin.java.KoinJavaComponent.getKoin().get<com.tcs.vehicleassistant.ToolManager>().getLlmToolsPrompt(query, lastAiResponse)
        lastInjectedTools = toolsString
        basePrompt.append("$toolsString\n\n")
        
        // --- DYNAMIC SENSOR RULES ---
        val customInstructions = VehicleManager.getCustomPropertyInstructions()
        if (customInstructions.isNotEmpty()) {
            basePrompt.append("=== DYNAMIC SENSOR RULES ===\n")
            customInstructions.forEachIndexed { index, inst ->
                basePrompt.append("${index + 1}. $inst\n")
            }
        }
        
        return basePrompt.toString().trimIndent()
    }

    /**
     * Recycles the LiteRT conversation to bound KV-cache growth.
     *
     * @return false when an inference is still in flight — the caller should wait and retry rather
     * than force-closing the conversation under a live stream.
     */
    fun resetConversation(context: Context? = null): Boolean {
        synchronized(inferenceLock) {
            if (engine == null) return true
            if (activeInferences > 0) {
                Log.w(TAG, "Skipping conversation reset: $activeInferences inference(s) still in flight")
                return false
            }

            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous conversation", e)
            }
            conversation = null
            lastAiResponse = ""

            try {
                conversation = engine?.createConversation(buildConversationConfig())
                isFirstMessage = true
                nativeTurnsSinceReset = 0
                Log.d(
                    TAG,
                    "Conversation reset. isFirstMessage=true backend=$activeBackendString " +
                        "speculativeWas=$speculativeDecodingActive"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset conversation", e)
                return false
            }
            return true
        }
    }

    var isPrewarmed = false

    /**
     * Releases the native engine to reclaim RAM.
     *
     * Refuses to close while an inference is still inside the engine — memory-pressure callbacks
     * and session teardown could otherwise free native state that a streaming callback was about
     * to touch, crashing the process.
     */
    fun unload(): Boolean {
        synchronized(inferenceLock) {
            if (activeInferences > 0) {
                Log.i(TAG, "Skipping unload: $activeInferences inference(s) still in flight.")
                return false
            }
            try {
                conversation?.close()
                engine?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanly close inference instance during unload.", e)
            } finally {
                conversation = null
                engine = null
                isFirstMessage = true
                isPrewarmed = false
                lastAiResponse = ""
                activeInferences = 0
                speculativeDecodingActive = false
                Log.i(TAG, "LLM model unloaded from memory to save resources.")
            }
            return true
        }
    }
}

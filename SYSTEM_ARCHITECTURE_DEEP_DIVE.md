# VehicleEdgeAssistant: System Architecture Deep Dive

## Executive Summary for Management

This document provides a **technical deep dive** into how the VehicleEdgeAssistant parses vehicle tools from JSON, injects them into the LLM system prompt, and executes them dynamically. The architecture is **zero-code extensible**, meaning new vehicle features can be added via JSON without modifying Kotlin code.

---

## Part 1: The Three-Tier Architecture Stack

The system operates on **three core components** that communicate in a specific order:

```
┌─────────────────────────────────────────────────────────┐
│ 1. INITIALIZATION PHASE (Startup)                       │
│    - Load vehicle_skills_registry.json                  │
│    - Parse tools into memory                            │
│    - Build semantic embeddings for RAG                  │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 2. PROMPT ENGINEERING PHASE (Per Query)                 │
│    - Read live vehicle telemetry                        │
│    - Filter relevant tools via semantic search          │
│    - Construct dynamic system prompt                    │
│    - Feed to LLM with user query                        │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 3. EXECUTION PHASE (Tool Interception)                  │
│    - Extract <TOOL>command(args)</TOOL> from LLM output │
│    - Execute handler (Kotlin or VHAL)                   │
│    - Return result to user                              │
└─────────────────────────────────────────────────────────┘
```

---

## Part 2: Data Structure - vehicle_skills_registry.json

The JSON file contains two main arrays: **properties** (sensor reads) and **tools** (actuator commands).

### 2.1 Properties Array (Sensor Reads)

**Purpose**: Allow the LLM to read real-time vehicle data and use it in reasoning.

```json
{
  "properties": [
    {
      "name": "ADAS_OSE_DOOR_ALERT",
      "id": 639631617,
      "type": "BOOLEAN",
      "instruction": "If the user asks about the door status, you MUST read the ADAS_OSE_DOOR_ALERT property from the Current State and answer exactly what it says."
    },
    {
      "name": "EV_BATTERY_LEVEL",
      "id": 291504905,
      "type": "FLOAT"
    },
    {
      "name": "TIRE_PRESSURE",
      "id": 392565865,
      "type": "FLOAT"
    }
  ]
}
```

**Field Breakdown**:
- **`name`**: Human-readable property identifier
- **`id`**: AOSP/VHAL Property ID (fixed integer, vendor-specific)
- **`type`**: Data type (`BOOLEAN`, `INT`, `FLOAT`, `STRING`)
- **`instruction`** (optional): Explicit constraint telling the LLM how to use this property

**What Happens**:
1. At startup, `VehicleManager.initialize()` reads this array
2. For each property, it subscribes to live updates via `CarPropertyManager.registerCallback()`
3. The property values are stored in `customPropertyValues` map
4. When generating the system prompt, `VehicleManager.getLLMContextString()` converts these values into human-readable strings:
   ```
   "ADAS_OSE_DOOR_ALERT: true, EV_BATTERY_LEVEL: 85.5, TIRE_PRESSURE: 32.0"
   ```

### 2.2 Tools Array (Actuator Commands)

**Purpose**: Define all possible LLM outputs and how they should be executed.

There are **two handler types**:

#### Type A: GENERIC_VHAL_WRITE (Zero-Code Hardware Writes)

```json
{
  "prompt_string": "<TOOL>turnOnAC()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 354419973,
  "data_type": "BOOLEAN",
  "area_id": 0,
  "value_to_write": "true",
  "success_message": "I've turned on the AC.",
  "keywords": ["ac", "air", "conditioning", "cool", "cold"],
  "constraints": [
    {
      "property_id": 291504647,
      "operator": "<",
      "value": 70,
      "error_msg": "Safety Warning: Speed is too high."
    }
  ]
}
```

**How it works**:
1. LLM outputs: `<TOOL>turnOnAC()</TOOL>`
2. `ToolManager.executeToolCall()` intercepts this
3. Checks `constraints`: Verifies current speed < 70 mph
4. If constraints pass, calls `VehicleManager.setPropertyVerified(354419973, 0, "true", "BOOLEAN")`
5. This writes directly to VHAL via `CarPropertyManager`
6. Returns: `"I've turned on the AC."`

**Key Fields**:
- **`prompt_string`**: The exact syntax the LLM will output
- **`handler_type`**: `GENERIC_VHAL_WRITE` bypasses Kotlin logic entirely
- **`property_id`**: VHAL ID to write to
- **`data_type`**: How to interpret the value
- **`area_id`**: Vehicle zone (0 = global, 1 = driver, 2 = passenger, etc.)
- **`value_to_write`**: Static value for this action
- **`keywords`**: For RAG semantic search filtering
- **`constraints`** (optional): Safety guardrails (e.g., windows only open below 70 mph)

#### Type B: CUSTOM_KOTLIN (Complex Logic)

```json
{
  "prompt_string": "<TOOL>setTemperature(VAL)</TOOL>",
  "handler_key": "setTemperature",
  "handler_type": "CUSTOM_KOTLIN",
  "keywords": ["temperature", "hot", "cold", "warm", "cool", "ac", "heater", "climate"]
}
```

**How it works**:
1. LLM outputs: `<TOOL>setTemperature(72)</TOOL>`
2. `ToolManager.executeToolCall()` matches `handler_key` = `"setTemperature"`
3. Extracts argument: `72`
4. Calls custom Kotlin handler in `when (matchedTool.handlerKey)` block
5. Performs complex logic (e.g., Celsius ↔ Fahrenheit conversion, bounds checking)
6. Returns result message

**Example Handler** (from ToolManager.kt, line 253-256):
```kotlin
"setTemperature" -> {
    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
    val success = VehicleManager.writeTemperatureToVhalVerified(value.toFloat())
    if (success) "I've set the temperature to $value degrees." else "System error..."
}
```

---

## Part 3: Initialization Phase - Loading Tools into Memory

### 3.1 ToolManager.initialize() - JSON Parsing

**Location**: `ToolManager.kt`, lines 46-112

```kotlin
fun initialize(context: Context) {
    val inputStream = context.assets.open("vehicle_skills_registry.json")
    val jsonStr = String(buffer, Charsets.UTF_8)
    val jsonObject = JSONObject(jsonStr)
    
    val toolsArray = jsonObject.getJSONArray("tools")
    for (i in 0 until toolsArray.length()) {
        val toolObj = toolsArray.getJSONObject(i)
        
        // Extract ALL fields from JSON
        val promptString = toolObj.getString("prompt_string")
        val handlerType = toolObj.optString("handler_type", "CUSTOM_KOTLIN")
        val handlerKey = toolObj.optString("handler_key", null)
        
        // Derive command name from prompt_string or handler_key
        val commandName = handlerKey ?: promptString
            .substringAfter("<TOOL>")
            .substringBefore("</TOOL>")
            .substringBefore("(")  // Extract just "setTemperature" from "<TOOL>setTemperature(VAL)</TOOL>"
        
        // Parse constraints array
        val constraintsList = mutableListOf<Constraint>()
        if (toolObj.has("constraints")) {
            val arr = toolObj.getJSONArray("constraints")
            for (j in 0 until arr.length()) {
                val cObj = arr.getJSONObject(j)
                constraintsList.add(Constraint(
                    propertyId = cObj.getInt("property_id"),
                    operator = cObj.getString("operator"),
                    value = cObj.getDouble("value"),
                    errorMsg = cObj.getString("error_msg")
                ))
            }
        }
        
        // Store in activeTools map
        activeTools[commandName] = ToolDefinition(
            handlerType, promptString, handlerKey, propertyId, dataType, areaId, 
            valueToWrite, successMessage, keywordsList, constraintsList
        )
    }
}
```

**Result**: `activeTools` map now contains:
```
{
  "setTemperature" -> ToolDefinition(...),
  "turnOnAC" -> ToolDefinition(...),
  "navigate" -> ToolDefinition(...),
  ...
}
```

### 3.2 SemanticSearchManager.buildToolEmbeddingsCache()

**Location**: `SemanticSearchManager.kt`, lines 49-61

After ToolManager loads tools, **semantic embeddings are computed**:

```kotlin
fun buildToolEmbeddingsCache() {
    val tools = ToolManager.getAllTools()  // Get all loaded tools
    
    for ((cmd, def) in tools) {
        // Combine handler_key + keywords into a single description
        val keywordsText = def.keywords?.joinToString(" ") ?: ""
        val description = "${def.handlerKey} $keywordsText"
        
        // Embed this description using MediaPipe Universal Sentence Encoder
        val vector = embedText(description)  // Returns FloatArray of ~512 dimensions
        
        if (vector != null) {
            toolEmbeddings[cmd] = vector  // Cache for later RAG search
        }
    }
}
```

**Why embeddings?**
- When user says: *"I'm too cold"*
- Embeddings allow finding semantically similar tools like `setTemperature`, `setSeatHeater`, `turnOnHeater`
- **Without embeddings**: Would only match exact keywords → LLM gets confused
- **With embeddings**: Cosine similarity finds the most relevant 30 tools to inject into prompt

---

## Part 4: Prompt Engineering Phase - Building the System Prompt

### 4.1 LLMManager.getDefaultSystemPrompt() - Core Architecture

**Location**: `LLMManager.kt`, lines 315-522

This is the **most critical function** where the prompt is constructed dynamically per query.

#### Step 1: Query Classification

```kotlin
suspend fun getDefaultSystemPrompt(context: android.content.Context, query: String = ""): String {
    val userQuery = query.lowercase()
    
    // Classify the query intent
    val isHvac = userQuery.contains("temperature") || userQuery.contains("hot") || ...
    val isFood = userQuery.contains("hungry") || userQuery.contains("food") || ...
    val isFuel = userQuery.contains("fuel") || userQuery.contains("gas") || ...
    val isNav = userQuery.contains("navigate") || userQuery.contains("go to") || ...
    
    // ... (more classifications)
}
```

**Why?** Different queries need different tools. If user asks "I'm hungry", we don't inject HVAC tools—too much prompt bloat.

#### Step 2: Build Base Prompt

```kotlin
val basePrompt = StringBuilder()
basePrompt.append("You are a concise In-Car AI Assistant...")

// SECTION 1: Vehicle State (Real-Time Telemetry)
basePrompt.append("=== VEHICLE STATE ===\n")
basePrompt.append("${VehicleManager.getLLMContextString(context)}\n")
basePrompt.append("Memory: $userMemory\n\n")

// SECTION 2: Available Tools (Filtered by RAG)
basePrompt.append("=== TOOLS ===\n")
basePrompt.append("${ToolManager.getLlmToolsPrompt(query)}\n\n")  // <-- Key call

// SECTION 3: Strict Rules
basePrompt.append("=== STRICT RULES ===\n")
basePrompt.append("IMPORTANT: If you use a tool, YOUR RESPONSE MUST EXACTLY START WITH THE XML TAG '<TOOL>'...\n")
```

#### Step 3: Dynamic Rules Based on Query Intent

```kotlin
if (isHvac || q.isEmpty()) {
    basePrompt.append("1. HVAC: To change the temperature, use the EXACT <TOOL> syntax...\n")
    // Examples showing how to use temperature tools
}

if (isFood || q.isEmpty()) {
    if (dynCtx.isNotEmpty()) {
        basePrompt.append("8. FOOD CHOICES: $dynCtx\n")  // Dynamic context from OpenStreetMap API
    }
}

if (isNav || q.isEmpty()) {
    basePrompt.append("Example 16:\nUser: \"Navigate to Tokyo\"\nAssistant: <TOOL>navigate(Tokyo)</TOOL>\n\n")
}
```

### 4.2 ToolManager.getLlmToolsPrompt() - Tool Filtering via RAG

**Location**: `ToolManager.kt`, lines 136-140

This is where **semantic search filters tools**:

```kotlin
fun getLlmToolsPrompt(query: String = ""): String {
    val relevantTools = getRelevantTools(query)  // <-- RAG filtering happens here
    if (relevantTools.isEmpty()) return ""
    
    // Convert filtered tools to prompt string
    return relevantTools.map { it.promptString }.joinToString("\n")
}

fun getRelevantTools(query: String): List<ToolDefinition> {
    if (query.isBlank()) return activeTools.values.toList()
    return SemanticSearchManager.search(query, 30)  // Return top 30 semantically similar tools
}
```

**Example**:
- **User**: "I'm cold"
- **Query embedding**: Vector representation of "cold"
- **Tool embeddings** already computed at startup
- **Cosine similarity** computed between query and each tool
- **Top 30 matches**: `setTemperature`, `setSeatHeater`, `turnOnHeater`, `increaseTemperature`, `turnOnAutoMode`, etc.
- **Injected into prompt**: Only these 30 tools, not all 50+

### 4.3 VehicleManager.getLLMContextString() - Real-Time Telemetry

**Location**: `VehicleManager.kt`, lines 41-48

Converts live vehicle data into human-readable strings for the LLM:

```kotlin
fun getLLMContextString(context: Context): String {
    val customProps = getCustomPropertiesString()  // "ADAS_OSE_DOOR_ALERT: true, EV_BATTERY_LEVEL: 85.5"
    
    return "Speed: ${getRealSpeed()}mph, " +
           "Temp: ${getRealTemperature()}F, " +
           "Heater: ${getRealSeatHeaterLevel()}, " +
           "City: ${LocationManager.getCurrentCity()}" +
           "$customProps"
}
```

**Output example**:
```
Speed: 45mph, Temp: 72F, Heater: 2, City: Tokyo, ADAS_OSE_DOOR_ALERT: true, EV_BATTERY_LEVEL: 85.5
```

This string is **injected at the top of the system prompt**, so the LLM always has fresh data.

---

## Part 5: LLM Inference & Tool Output

### 5.1 Conversation Flow in LLMManager

**Location**: `LLMManager.kt`, lines 20-154

```kotlin
object LLMManager {
    var engine: Engine? = null         // Google LiteRT engine (C++ backend)
    var conversation: Conversation? = null  // Maintains conversation state + KV cache
    var isFirstMessage = true
    
    suspend fun initialize(context: Context, modelPath: String, ...) {
        // Load .litertlm/.bin model file
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,  // GPU, NPU, or CPU
            maxNumTokens = maxTokens  // KV cache size (default 2048)
        )
        
        engine = Engine(engineConfig)
        engine!!.initialize()
        
        // Create a conversation state
        resetConversation(context)
    }
    
    fun resetConversation(context: Context? = null) {
        val conversationConfig = ConversationConfig()
        conversation = engine!!.createConversation(conversationConfig)
        isFirstMessage = true  // Signal to inject FULL system prompt on first turn
    }
}
```

### 5.2 How System Prompt is Used (First Turn)

**First message flow**:
1. User says: "I'm cold"
2. `AssistantSession` calls `LLMManager.getDefaultSystemPrompt(context, "I'm cold")`
3. System prompt is **1500+ tokens**, containing:
   - Vehicle state
   - Filtered tools (setTemperature, setSeatHeater, etc.)
   - Strict rules + examples
4. LiteRT engine computes **KV cache** for this entire system prompt (compute-heavy, ~2-3 seconds)
5. Then appends user query: "I'm cold"
6. LLM generates response: `<TOOL>setTemperature(75)</TOOL> I'm warming it up.`

**Performance optimization** (Differential KV Cache):
- **First message**: System prompt + user query → full computation
- **Follow-up**: Only new user query → KV cache reused, only delta computed (~500ms)

---

## Part 6: Execution Phase - Tool Interception & Execution

### 6.1 AssistantSession - Streaming Token Interception

When LLM generates tokens, they stream in real-time. The system uses **regex to intercept `<TOOL>` tags**:

```kotlin
// Pseudo-code (from AssistantSession.kt logic)
val toolRegex = Regex("<TOOL>(.*?)</TOOL>")

for (token in llmTokenStream) {
    // Check if token contains a tool call
    if (token.contains("<TOOL>")) {
        val match = toolRegex.find(token)
        if (match != null) {
            val toolCall = match.groupValues[1]  // Extract "setTemperature(75)"
            
            // SUPPRESS from UI (don't show `<TOOL>` tags to user)
            // EXECUTE the tool silently
            val result = ToolManager.executeToolCall(context, toolCall)
            
            // Feed result back to LLM for agentic loop
            // (This allows LLM to make follow-up decisions)
        }
    } else {
        // Regular text → display to user
        displayToUI(token)
    }
}
```

### 6.2 ToolManager.executeToolCall() - The Handler Router

**Location**: `ToolManager.kt`, lines 146-577

This is the **execution engine**:

```kotlin
suspend fun executeToolCall(context: Context, rawToolCall: String): String {
    val toolCall = rawToolCall.trim()
    
    // Step 1: Find matching tool definition from JSON
    var matchedTool: ToolDefinition? = null
    for ((key, def) in activeTools) {
        if (toolCall.lowercase().startsWith(key.lowercase())) {
            matchedTool = def
            break
        }
    }
    
    if (matchedTool == null) {
        return "System Error: The requested tool is not supported."
    }
    
    // Step 2: Safety Constraint Validation
    if (matchedTool.constraints != null) {
        for (constraint in matchedTool.constraints) {
            val currentValue = VehicleManager.getFloatProperty(constraint.propertyId)
            val failed = when (constraint.operator) {
                "<" -> currentValue.toDouble() >= constraint.value
                ">" -> currentValue.toDouble() <= constraint.value
                "==" -> currentValue.toDouble() != constraint.value
                else -> false
            }
            if (failed) {
                return constraint.errorMsg  // Block execution with safety message
            }
        }
    }
    
    // Step 3: Route to appropriate handler
    return when (matchedTool.handlerType) {
        
        "GENERIC_VHAL_WRITE" -> {
            // Direct hardware write (no Kotlin logic needed)
            val propId = matchedTool.propertyId!!
            val valueToSet = matchedTool.valueToWrite ?: toolCall.substringAfter("(").substringBefore(")")
            
            val success = VehicleManager.setPropertyVerified(propId, areaId, valueToSet, dataType)
            if (success) matchedTool.successMessage ?: "Done." else "Hardware failed."
        }
        
        "CUSTOM_KOTLIN" -> {
            // Route to specific handler
            when (matchedTool.handlerKey) {
                "setTemperature" -> {
                    val value = toolCall.substringAfter("(").substringBefore(")").toDoubleOrNull() ?: 72.0
                    val success = VehicleManager.writeTemperatureToVhalVerified(value.toFloat())
                    if (success) "I've set the temperature to $value degrees." else "System error."
                }
                "navigate" -> {
                    val dest = toolCall.substringAfter("(").substringBefore(")")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$dest"))
                    context.startActivity(intent)
                    "Navigating to $dest."
                }
                "playMusic" -> {
                    val query = toolCall.substringAfter("(").substringBefore(")")
                    // Connect to MediaBrowser and dispatch playFromSearch
                    "Playing $query."
                }
                else -> "System Error: Handler not found."
            }
        }
    }
}
```

### 6.3 VehicleManager.setPropertyVerified() - Hardware Write with Verification

**Location**: `VehicleManager.kt`, lines 452-558

This is the **critical hardware safety layer**:

```kotlin
suspend fun setPropertyVerified(
    propertyId: Int, 
    targetAreaId: Int, 
    value: String, 
    dataType: String,
    timeoutMs: Long = 1500,
    maxRetries: Int = 3
): Boolean {
    // Pre-check: Is value already set? Skip redundant VHAL call
    val currentValue = when (dataType.uppercase()) {
        "INT" -> carPropertyManager?.getIntProperty(propertyId, targetAreaId)?.toString()
        "FLOAT" -> carPropertyManager?.getFloatProperty(propertyId, targetAreaId)?.toString()
        "BOOLEAN" -> carPropertyManager?.getBooleanProperty(propertyId, targetAreaId)?.toString()
        else -> null
    }
    if (currentValue == value) return true
    
    // Retry loop with exponential backoff
    var currentDelay = 500L
    repeat(maxRetries) { attempt ->
        val success = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                // Register callback to verify the write succeeded
                val callback = object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(valueRecord: CarPropertyValue<*>) {
                        if (valueRecord.propertyId == propertyId && valueRecord.areaId == targetAreaId) {
                            val matches = valueRecord.value == value.toFloatOrNull()?.toInt() ?: ...
                            if (matches) {
                                carPropertyManager?.unregisterCallback(this, propertyId)
                                continuation.resume(true)  // ✅ SUCCESS
                            }
                        }
                    }
                    override fun onErrorEvent(propId: Int, zone: Int) {
                        carPropertyManager?.unregisterCallback(this, propertyId)
                        continuation.resume(false)  // ❌ HARDWARE ERROR
                    }
                }
                
                // Register callback BEFORE writing
                carPropertyManager?.registerCallback(callback, propertyId, SENSOR_RATE_ONCHANGE)
                
                // Attempt the write
                try {
                    setGenericVhalProperty(propertyId, targetAreaId, value, dataType)
                } catch (e: Exception) {
                    carPropertyManager?.unregisterCallback(callback, propertyId)
                    continuation.resume(false)
                }
                
                // Timeout fallback
                launch { delay(timeoutMs); continuation.resume(false) }
            }
        } ?: false
        
        if (success) return true
        
        // Exponential backoff before retry
        if (attempt < maxRetries - 1) {
            delay(currentDelay)
            currentDelay *= 2
        }
    }
    
    return false
}
```

**Key features**:
- ✅ **Verification**: Waits for VHAL callback confirming the change
- ✅ **Timeout handling**: Doesn't hang if hardware is slow
- ✅ **Retry logic**: Exponential backoff (500ms → 1s → 2s)
- ✅ **Safety**: Prevents double-writes

---

## Part 7: Complete End-to-End Flow

**User says: "Turn on the AC"**

```
1. AUDIO INPUT
   ↓
   WakeWordService (Vosk) detects "Hey Auto"
   → Activates AssistantSession overlay
   
2. SPEECH-TO-TEXT
   ↓
   Android SpeechRecognizer converts "Turn on the AC" → "turn on the ac"
   
3. PROMPT ENGINEERING
   ↓
   LLMManager.getDefaultSystemPrompt("turn on the ac")
   → Query classification: isHvac = true
   → Fetches live speed from VehicleManager: "Speed: 45mph"
   → Calls ToolManager.getLlmToolsPrompt("turn on the ac")
     → SemanticSearchManager.search() returns top 30 relevant tools
     → Extracts prompt_strings: "<TOOL>turnOnAC()</TOOL>", "<TOOL>turnOffAC()</TOOL>", etc.
   → Constructs 1500-token system prompt
   → Concatenates user query: "Turn on the AC"
   
4. LLM INFERENCE
   ↓
   LiteRT engine (Gemma 2B, GPU delegate):
   → Computes KV cache for system prompt + query
   → Generates tokens: "<TOOL>turnOnAC()</TOOL> I've turned on the AC."
   
5. TOKEN STREAMING & TOOL INTERCEPTION
   ↓
   AssistantSession streams tokens:
   → Token 1: "<"
   → Token 2: "TOOL"
   → ...
   → Token N: ">" (Completes <TOOL>turnOnAC()</TOOL>)
     → Regex match! Extract: "turnOnAC()"
     → SUPPRESS from UI display
     → Call ToolManager.executeToolCall("turnOnAC()")
   
6. TOOL EXECUTION
   ↓
   ToolManager.executeToolCall("turnOnAC()"):
   → Find matching tool: matchedTool = activeTools["turnOnAC"]
   → Check constraints: currentSpeed (45) < maxSpeed (70) ✅ PASS
   → handlerType = "GENERIC_VHAL_WRITE"
   → Extract propertyId = 354419973, value = "true"
   → Call VehicleManager.setPropertyVerified(354419973, 0, "true", "BOOLEAN")
     → Pre-check: current AC state = "false"
     → Call carPropertyManager.setBooleanProperty(354419973, 0, true)
     → Register callback to listen for VHAL confirmation
     → Wait for onChangeEvent (timeout 1500ms)
     → ✅ Receives onChangeEvent with new value = true
   → Return: "I've turned on the AC."
   
7. TEXT-TO-SPEECH STREAMING
   ↓
   AssistantSession:
   → Continues streaming remaining tokens after <TOOL> call
   → Token N+1: "I"
   → Token N+2: "'ve turned on the AC."
   → Feed to TextToSpeech engine (by sentence)
   → Sentence-boundary regex: `(?<=[a-z])[.!?]` detects end of sentence
   → Push "I've turned on the AC." to TTS immediately (don't wait for full response)
   
8. AUDIO OUTPUT
   ↓
   Speaker: "I've turned on the AC."
   UI: Display text with confirmation icon ✅
```

---

## Part 8: Memory Management

### MemoryManager - Conversation History

**Location**: `MemoryManager.kt`

Maintains a **sliding window** of conversation history:

```kotlin
object MemoryManager {
    private val conversationHistory = mutableListOf<Turn>()
    
    fun addTurn(role: String, content: String) {
        conversationHistory.add(Turn(role, content))
    }
    
    fun getSlidingWindowContext(maxChars: Int): String {
        val sb = StringBuilder()
        var currentLength = 0
        
        // Iterate backwards to keep most recent messages
        for (i in conversationHistory.indices.reversed()) {
            val turn = conversationHistory[i]
            val turnString = "${turn.role}: ${turn.content}\n"
            
            if (currentLength + turnString.length > maxChars) {
                // Remove old turns to prevent memory leak
                conversationHistory.subList(0, i + 1).clear()
                break
            }
            currentLength += turnString.length
        }
        
        return sb.toString().trim()
    }
}
```

**Why this design?**
- **Infinite conversations**: Without a sliding window, KV cache would grow unbounded
- **Smart truncation**: Keeps most recent context, drops old turns
- **LLM injection**: `LLMManager.getSystemPrompt()` calls `MemoryManager.getSlidingWindowContext(500)` to include recent conversation (500 chars)

---

## Part 9: Key Design Patterns

### 9.1 Factory Pattern (ToolDefinition)

The `ToolDefinition` data class acts as a **factory for tool handlers**:

```kotlin
data class ToolDefinition(
    val handlerType: String,      // "GENERIC_VHAL_WRITE" or "CUSTOM_KOTLIN"
    val promptString: String,     // What LLM outputs
    val handlerKey: String?,      // Which Kotlin handler to call
    val propertyId: Int?,         // VHAL property (for generic writes)
    val dataType: String?,        // INT, FLOAT, BOOLEAN, STRING
    val areaId: Int?,             // Vehicle zone
    val valueToWrite: String?,    // Static value
    val successMessage: String?,  // Confirmation text
    val keywords: List<String>?,  // For RAG
    val constraints: List<Constraint>?,  // Safety guardrails
    val requiresConfirmation: Boolean,   // User confirmation needed?
    val confirmationMessage: String?     // What to ask user
)
```

### 9.2 Singleton Pattern (Managers)

All core managers are **Kotlin objects** (singletons):

```kotlin
object LLMManager { ... }
object ToolManager { ... }
object VehicleManager { ... }
object MemoryManager { ... }
object SemanticSearchManager { ... }
```

**Why?**
- Single state across app lifetime
- Thread-safe by JVM guarantee
- Efficient initialization (lazy loaded on first access)

### 9.3 Repository Pattern (JSON as DB)

`vehicle_skills_registry.json` acts as a **declarative database**:
- Schema: Tool definitions + properties
- ORM: ToolManager parses JSON into Kotlin objects
- Query: SemanticSearchManager searches by relevance

### 9.4 Observer Pattern (CarPropertyManager Callbacks)

Vehicle telemetry updates via callbacks:

```kotlin
carPropertyManager?.registerCallback(carPropertyCallback, VehiclePropertyIds.HVAC_TEMPERATURE_SET, SENSOR_RATE_ONCHANGE)

private val carPropertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
    override fun onChangeEvent(value: CarPropertyValue<*>) {
        when (value.propertyId) {
            VehiclePropertyIds.HVAC_TEMPERATURE_SET -> currentTemperature = value.value as Float
        }
    }
}
```

When the user physically adjusts temperature via buttons, the system is **automatically notified**.

---

## Part 10: Zero-Code Extension Example

### Adding a New Feature: Sunroof Control

**Step 1: Add to JSON** (No Kotlin code needed!)

```json
{
  "prompt_string": "<TOOL>openSunroof()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 320865540,
  "data_type": "INT",
  "area_id": 16,
  "value_to_write": "100",
  "success_message": "Opening the sunroof.",
  "keywords": ["sunroof", "roof", "sky", "window", "open"]
}
```

**What happens automatically**:
1. ToolManager loads this tool at startup → added to `activeTools`
2. SemanticSearchManager embeds keywords → added to `toolEmbeddings`
3. User says "Open the sunroof"
4. Semantic search finds this tool (high cosine similarity with "open" + "sunroof")
5. Tool injected into prompt
6. LLM outputs: `<TOOL>openSunroof()</TOOL>`
7. ToolManager intercepts → GENERIC_VHAL_WRITE routes to VehicleManager
8. Sunroof opens via VHAL

**Zero code changes to Kotlin!**

---

## Part 11: Performance Metrics

| Phase | Latency | Optimization |
|-------|---------|---------------|
| **Initialization** | ~2s | Lazy loading, cached embeddings |
| **Query Classification** | ~10ms | Simple string matching |
| **RAG Search** | ~20ms | Pre-computed embeddings + cosine similarity |
| **System Prompt Construction** | ~50ms | StringBuilder, minimal I/O |
| **LLM Inference (First Turn)** | 1.5-3.5s | GPU/NPU delegates, KV cache prefill |
| **LLM Inference (Follow-up)** | 0.5-1.5s | Differential KV cache |
| **Tool Interception** | ~5ms | Regex pattern matching |
| **VHAL Write** | 100-500ms | Retry logic, exponential backoff |
| **TTS Streaming** | Parallel | Sentence-boundary chunking |

---

## Conclusion

The VehicleEdgeAssistant uses a **3-phase architecture**:

1. **Initialization**: Load tools from JSON, build semantic embeddings
2. **Prompt Engineering**: Query classification, RAG filtering, telemetry injection, dynamic prompts
3. **Execution**: Tool interception via regex, routing to GENERIC or CUSTOM handlers, VHAL writes with verification

**Key innovation**: Completely extensible via JSON—add new features without touching Kotlin code. Semantic search ensures the LLM only sees relevant tools, preventing prompt bloat and hallucination.

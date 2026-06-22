# VehicleEdgeAssistant: Comprehensive FAQ

Frequently asked questions with detailed answers to help you understand the complete architecture.

---

## TABLE OF CONTENTS

1. [General Questions](#general-questions)
2. [Architecture & Design](#architecture--design)
3. [System Components](#system-components)
4. [Tool System](#tool-system)
5. [LLM & Inference](#llm--inference)
6. [Hardware Integration](#hardware-integration)
7. [Performance & Optimization](#performance--optimization)
8. [Security & Safety](#security--safety)
9. [Development & Deployment](#development--deployment)
10. [Troubleshooting](#troubleshooting)

---

## GENERAL QUESTIONS

### Q1: What is VehicleEdgeAssistant?

**A:** VehicleEdgeAssistant is an on-device AI voice assistant for Android Automotive that:
- Runs entirely on the vehicle (no cloud dependency)
- Understands natural language via Google's LiteRT LLM engine
- Controls vehicle hardware (HVAC, windows, lights, etc.) safely
- Is completely extensible via JSON (zero-code feature additions)
- Responds to user commands in ~3 seconds

**Key Innovation:** You can add new vehicle features by editing a JSON file, without writing any Kotlin code.

---

### Q2: Why on-device only? What about cloud models?

**A:** On-device has massive advantages:

| Aspect | On-Device | Cloud |
|--------|-----------|-------|
| **Latency** | 2-3 seconds | 5-10+ seconds |
| **Privacy** | 100% (no data leaves vehicle) | Data sent to servers |
| **Internet** | Works offline perfectly | Requires connectivity |
| **Cost** | One-time model cost | Per-request API fees |
| **Vehicle Safety** | Instant response (critical for driving) | Network delays dangerous |

**Optional:** We also support cloud APIs (Gemini, Claude) as fallback when needed.

---

### Q3: What models are supported?

**A:** We support multiple LLM models optimized for different hardware tiers:

1. **Entry-Level** (100MB)
   - SmolLM 135M
   - Runs on any Android device
   - ~1 second response time

2. **Mid-Range** (1.6-3GB)
   - Qwen 2.5 1.5B (supports 4K context)
   - Gemma 2B
   - ~2.5 seconds response time

3. **Premium** (3GB+)
   - Gemma-4 2.5B
   - Llama 3.2 3B
   - ~3.5 seconds response time

Models can be swapped at runtime via UI dropdown. No recompilation needed.

---

### Q4: Can I add new vehicle features without coding?

**A:** **YES! This is the core innovation.**

**Traditional approach:**
1. Write Kotlin handler code (500+ lines)
2. Test on multiple devices
3. Compile APK
4. Deploy via Google Play
5. User installs update
6. **Total: 2-4 weeks**

**VehicleEdgeAssistant approach:**
1. Edit `vehicle_skills_registry.json` (add 8 lines)
2. System auto-loads, embeddings computed, works!
3. **Total: 5 minutes**

Example: Adding sunroof control
```json
{
  "prompt_string": "<TOOL>openSunroof()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 320865540,
  "value_to_write": "100",
  "keywords": ["sunroof", "roof", "sky"]
}
```

Done! No Kotlin code needed.

---

## ARCHITECTURE & DESIGN

### Q5: What are the main layers of the architecture?

**A:** Six core layers:

1. **User Input Layer** 🎤
   - Vosk wakeword detection
   - Android SpeechRecognizer (STT)

2. **Prompt Engineering Layer** 🧠
   - Query classification
   - Live vehicle telemetry injection
   - RAG semantic tool filtering
   - Dynamic system prompt construction

3. **LLM Inference Layer** 💡
   - Google LiteRT engine
   - GPU/NPU/CPU backends
   - Real-time token streaming

4. **Tool Interception Layer** 🛠️
   - Regex pattern matching
   - Tool extraction from LLM output
   - Handler routing (GENERIC_VHAL_WRITE vs CUSTOM_KOTLIN)

5. **Hardware Execution Layer** 🚗
   - VHAL property writes
   - Verification callbacks
   - Safety constraint validation

6. **Output Layer** 🔊
   - Text-to-Speech synthesis
   - Speaker audio playback
   - UI display with confirmation icons

---

### Q6: What is "RAG" and why does it matter?

**A:** RAG = **Retrieval-Augmented Generation**

**Problem:** If we inject all 50+ tools into the LLM prompt:
- Prompt becomes 3000+ tokens (bloated)
- LLM gets confused (sees tools it shouldn't use)
- Response becomes slower
- LLM might "hallucinate" tools

**Solution: Smart Tool Filtering**

User says: "I'm cold"
```
1. Embed query: "i'm cold" → Vector[512]
2. Compare with all tool embeddings (pre-computed at startup)
3. Cosine similarity scores:
   • setTemperature: 0.95 ✅ (TOP MATCH)
   • setSeatHeater: 0.88 ✅
   • turnOnHeater: 0.85 ✅
   • navigate: 0.15 ❌ (filtered out)
   • playMusic: 0.12 ❌ (filtered out)
4. Select top 30 tools only
5. Inject into prompt
```

**Benefits:**
- ✅ Smaller prompt (only relevant tools)
- ✅ LLM sees correct context
- ✅ Faster inference
- ✅ Zero hallucination

---

### Q7: How does the system handle conversation history?

**A:** **Sliding Window Memory Management**

MemoryManager maintains conversation history but with smart truncation:

```kotlin
fun getSlidingWindowContext(maxChars: Int = 500): String {
    // Keep most recent turns only
    // Delete old turns to stay under maxChars limit
    // Prevents KV cache explosion
}
```

**Example:**

Turn 1: "What's the weather?" → 50 chars
Turn 2: "Turn on AC" → 30 chars
Turn 3: "Set temperature to 72" → 40 chars
...
Turn N: "Show me navigation" → 35 chars
**Total: ~500 chars (recent 6-8 turns)**

**Old turns automatically deleted** ✓

**Benefits:**
- ✅ KV cache stays bounded (2048 tokens max)
- ✅ Infinite conversations possible
- ✅ No app crashes from memory overflow
- ✅ LLM always has recent context

---

### Q8: What is the difference between GENERIC_VHAL_WRITE and CUSTOM_KOTLIN?

**A:** Two handler types for different complexity levels:

#### GENERIC_VHAL_WRITE (90% of tools)
**Use when:** Simple hardware writes (no logic needed)

```json
{
  "prompt_string": "<TOOL>turnOnAC()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 354419973,
  "value_to_write": "true",
  "data_type": "BOOLEAN"
}
```

**Execution:** Zero Kotlin code. ToolManager directly writes value to VHAL.

#### CUSTOM_KOTLIN (10% of tools)
**Use when:** Complex logic, conversions, multiple steps

```json
{
  "prompt_string": "<TOOL>setTemperature(VAL)</TOOL>",
  "handler_key": "setTemperature",
  "handler_type": "CUSTOM_KOTLIN",
  "keywords": ["temperature", "hot", "cold", "warm"]
}
```

**Execution:** Custom Kotlin handler in ToolManager.kt:
```kotlin
"setTemperature" -> {
    val value = toolCall.substringAfter("(").toDouble()
    // Complex logic: Convert C→F, validate range, etc
    val success = VehicleManager.writeTemperatureToVhalVerified(value)
    return "I've set temperature to $value degrees."
}
```

**Decision Tree:**
```
Need new feature?
  ├─ Is it simple ON/OFF or direct value write?
  │   └─ YES → GENERIC_VHAL_WRITE (5 min, JSON only)
  └─ Does it need math, conversions, or complex logic?
      └─ YES → CUSTOM_KOTLIN (Requires code)
```

---

## SYSTEM COMPONENTS

### Q9: What does LLMManager do?

**A:** **LLMManager = System Orchestrator & Prompt Builder**

**Responsibilities:**
1. Initialize LiteRT engine
2. Build dynamic system prompts per query
3. Manage conversation state (KV cache)
4. Classify query intent (isHvac? isFood? isNav?)
5. Fetch live telemetry from VehicleManager
6. Get filtered tools from ToolManager
7. Send prompt to LLM
8. Handle token streaming

**Key Method:**
```kotlin
suspend fun getDefaultSystemPrompt(
    context: Context,
    query: String = ""
): String {
    // 1. Classify query intent
    val isHvac = query.contains("temperature") || ...
    
    // 2. Fetch telemetry
    val telemetry = VehicleManager.getLLMContextString()
    
    // 3. Get filtered tools
    val toolPrompt = ToolManager.getLlmToolsPrompt(query)
    
    // 4. Build full prompt
    val systemPrompt = StringBuilder()
    systemPrompt.append("You are a concise in-car assistant...\n")
    systemPrompt.append("=== VEHICLE STATE ===\n")
    systemPrompt.append(telemetry)
    systemPrompt.append("=== TOOLS ===\n")
    systemPrompt.append(toolPrompt)
    // ... more rules ...
    
    return systemPrompt.toString()
}
```

---

### Q10: What does ToolManager do?

**A:** **ToolManager = Tool Parser & Execution Engine**

**Responsibilities:**
1. Parse `vehicle_skills_registry.json` at startup
2. Store tools in `activeTools` map
3. Get filtered tools via semantic search
4. Execute tool calls (intercept `<TOOL>...</TOOL>`)
5. Route to appropriate handler (GENERIC_VHAL_WRITE or CUSTOM_KOTLIN)
6. Validate safety constraints
7. Return result to user

**Key Methods:**
```kotlin
fun initialize(context: Context)
  └─ Load JSON, parse all tools into activeTools map

fun getLlmToolsPrompt(query: String): String
  └─ Get filtered tools for this query via RAG

suspend fun executeToolCall(
    context: Context,
    toolCall: String
): String
  └─ Find tool → Check constraints → Route to handler → Execute
```

---

### Q11: What does VehicleManager do?

**A:** **VehicleManager = Hardware Bridge**

**Responsibilities:**
1. Create CarPropertyManager instance
2. Subscribe to vehicle telemetry (speed, temp, battery, etc)
3. Read live sensor values
4. Convert to human-readable strings for LLM
5. Write properties to VHAL with verification
6. Handle hardware callbacks and retries

**Key Methods:**
```kotlin
fun getLLMContextString(): String
  └─ "Speed: 45mph, Temp: 72F, Battery: 85%..."

suspend fun setPropertyVerified(
    propertyId: Int,
    value: String,
    dataType: String
): Boolean
  └─ Write to VHAL with callback verification + retry logic
```

---

### Q12: What does SemanticSearchManager do?

**A:** **SemanticSearchManager = RAG Engine**

**Responsibilities:**
1. Initialize MediaPipe Universal Sentence Encoder (TFLite model)
2. Build tool embedding cache at startup
3. Embed user queries in real-time
4. Compute cosine similarity with all tools
5. Return top 30 semantically similar tools
6. Enable smart tool filtering (RAG)

**Key Methods:**
```kotlin
fun initialize(context: Context)
  └─ Load TFLite Universal Sentence Encoder model

fun buildToolEmbeddingsCache()
  └─ For each tool, embed keywords → store vectors

fun search(query: String, topK: Int = 30): List<ToolDefinition>
  └─ Embed query, compute similarity, return top 30
```

**Algorithm:**
```
Cosine Similarity = (v1 · v2) / (||v1|| * ||v2||)
Result: 0 (completely different) → 1 (identical)
```

---

### Q13: What does AssistantSession do?

**A:** **AssistantSession = Voice Overlay UI & Token Processor**

**Responsibilities:**
1. Create glassmorphism voice overlay window
2. Capture user speech via SpeechRecognizer
3. Stream tokens from LLM in real-time
4. Intercept `<TOOL>...</TOOL>` tags via regex
5. Execute tool calls silently
6. Display text to user
7. Push to TTS engine sentence-by-sentence
8. Show confirmation icons for successful actions

**Key Logic:**
```kotlin
val toolRegex = Regex("<TOOL>(.*?)</TOOL>")

for (token in llmTokenStream) {
    val match = toolRegex.find(token)
    if (match != null) {
        val toolCall = match.groupValues[1]
        // SUPPRESS from UI
        // EXECUTE tool silently
        ToolManager.executeToolCall(context, toolCall)
    } else {
        // Display normal text to user
        displayToUI(token)
        // Push to TTS
        pushToTTS(token)
    }
}
```

---

### Q14: What does MemoryManager do?

**A:** **MemoryManager = Conversation History Manager**

**Responsibilities:**
1. Store conversation turns (user & assistant messages)
2. Maintain sliding window (max 500 chars of history)
3. Provide context to LLMManager for prompt injection
4. Auto-delete old turns when max size exceeded
5. Support format conversion (Gemini API vs Anthropic Claude API)

**Key Methods:**
```kotlin
fun addTurn(role: String, content: String)
  └─ Add message to conversation history

fun getSlidingWindowContext(maxChars: Int = 500): String
  └─ Return recent turns fitting in maxChars limit

fun getGeminiHistory(): List<JSONObject>
  └─ Format history for Google Gemini API

fun getAnthropicHistory(): List<JSONObject>
  └─ Format history for Anthropic Claude API
```

---

## TOOL SYSTEM

### Q15: How are tools defined in the JSON?

**A:** Each tool has these fields:

```json
{
  "prompt_string": "<TOOL>commandName(ARGS)</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE or CUSTOM_KOTLIN",
  "handler_key": "commandName (for CUSTOM_KOTLIN)",
  "property_id": 354419973,
  "data_type": "BOOLEAN|INT|FLOAT|STRING",
  "area_id": 0,
  "value_to_write": "true/100/3.14",
  "success_message": "I've completed the action",
  "keywords": ["ac", "air", "conditioning", "cool"],
  "constraints": [
    {
      "property_id": 291504647,
      "operator": "<|>|==|!=|<=|>=",
      "value": 70,
      "error_msg": "Safety violation message"
    }
  ]
}
```

**Field Explanations:**
- **prompt_string**: Exact syntax LLM will output
- **handler_type**: How to execute (direct VHAL or custom Kotlin)
- **property_id**: VHAL property to read/write
- **data_type**: How to interpret the value
- **area_id**: Vehicle zone (0=global, 1=driver, 2=passenger, etc)
- **keywords**: Used for semantic search ranking
- **constraints**: Safety checks before execution

---

### Q16: How do safety constraints work?

**A:** Each tool can have constraint rules:

```json
"constraints": [
  {
    "property_id": 291504647,    // Current speed property
    "operator": "<",              // Speed MUST BE LESS THAN
    "value": 70,                  // 70 mph
    "error_msg": "Speed too high to open windows safely"
  }
]
```

**Execution Flow:**
```
LLM outputs: <TOOL>openWindows()</TOOL>
       ↓
ToolManager.executeToolCall():
  1. Find tool in activeTools
  2. For each constraint:
     • Read current value: speed = 45 mph
     • Evaluate: 45 < 70?
     • If FALSE → BLOCK action, return error message
     • If TRUE → Continue to next constraint
  3. All constraints passed? → Execute tool
  4. Any constraint failed? → Return error to user
```

**Common Constraints:**
```json
// Windows: Only open when not moving fast
{ "property_id": 291504647, "operator": "<", "value": 70 }

// Sunroof: Only open in good weather
{ "property_id": 392565865, "operator": ">", "value": 32 }

// Doors: Only unlock when car is stopped
{ "property_id": 291504647, "operator": "==", "value": 0 }
```

---

### Q17: How are new tools added to the system?

**A:** Three-step process:

#### Step 1: Edit JSON (vehicle_skills_registry.json)
```json
{
  "prompt_string": "<TOOL>openSunroof()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 320865540,
  "value_to_write": "100",
  "keywords": ["sunroof", "roof", "sky", "window"]
}
```

#### Step 2: System Auto-Loads (No code needed!)
- ToolManager parses JSON at startup ✓
- Tool added to activeTools map ✓
- Keywords embedded by SemanticSearchManager ✓
- Embeddings cached for RAG ✓

#### Step 3: Ready to Use!
User says: "Open the sunroof"
```
Query classification: sunroof = TRUE ✓
RAG search: "open sunroof" matches embeddings ✓
Tool injected into prompt ✓
LLM outputs: <TOOL>openSunroof()</TOOL> ✓
ToolManager executes ✓
Sunroof opens ✓
```

**No compilation, no testing, no deployment!**

---

## LLM & INFERENCE

### Q18: How does token streaming work?

**A:** Real-time token-by-token generation from LLM:

```
LiteRT Engine generates response:
       ↓
Token 1: "<"
Token 2: "T"
Token 3: "O"
Token 4: "O"
Token 5: "L"
Token 6: ">"
Token 7: "t"
Token 8: "u"
Token 9: "r"
Token 10: "n"
Token 11: "O"
Token 12: "n"
Token 13: "A"
Token 14: "C"
Token 15: "("
Token 16: ")"
Token 17: "<"
Token 18: "/"
Token 19: "T"
Token 20: "O"
Token 21: "O"
Token 22: "L"
Token 23: ">"
Token 24: " "
Token 25: "I"
Token 26: "'"
Token 27: "v"
Token 28: "e"
...
       ↓
AssistantSession receives tokens and:
1. Accumulates into buffer
2. Checks for <TOOL>...</TOOL> patterns
3. Executes tools when matched
4. Displays remaining text to user
5. Pushes to TTS (by sentence)
```

**Key Benefit:** User hears response starting at Token 1, not waiting for full generation!

---

### Q19: What is "First Token Latency" (TTFT)?

**A:** Time from sending prompt to receiving first token from LLM.

**Breakdown:**
```
LLMManager sends prompt to LiteRT
       ↓
LiteRT GPU Processing:
  1. Tokenize input prompt
  2. Prefill phase: Compute KV cache for entire prompt
     (This is expensive! Heavy GPU work)
  3. Decode phase: Generate first token
       ↓
First token received by AssistantSession
```

**Typical TTFT by Model:**
- SmolLM 135M: ~1.0-1.5 seconds
- Qwen 2.5 1.5B: ~1.5-2.5 seconds
- Gemma 2B: ~2.5-3.5 seconds

**Optimization: Differential KV Cache**

**First message:**
- System prompt (1500 tokens) + query
- Full computation needed
- TTFT: 2-3 seconds ⏱️

**Follow-up messages:**
- Only new query (50 tokens)
- KV cache for system prompt reused!
- Only delta computed
- TTFT: 0.5-1.5 seconds ⚡

---

### Q20: What is KV Cache and why does it matter?

**A:** Key-Value cache = Stored intermediate computations for speed

**Without KV Cache (naive approach):**
```
User: "Turn on the AC"
→ LLM recomputes entire system prompt → Slow (2-3s)

Follow-up: "Set temperature to 72"
→ LLM recomputes ENTIRE system prompt AGAIN → Slow (2-3s)
→ Wastes computation on same system prompt!
```

**With KV Cache (optimized):**
```
User: "Turn on the AC"
→ LLM computes system prompt → Stores KV cache → Slow (2-3s)

Follow-up: "Set temperature to 72"
→ LLM reuses cached system prompt computation ✓
→ Only computes new query (50 tokens) → Fast (0.5-1.5s)
```

**Memory Overhead:**
- KV cache size = 2048 tokens × model_size
- Typical: 200-500MB per conversation
- Sliding window truncation prevents overflow

---

### Q21: How does the LLM know which tools to use?

**A:** Three mechanisms work together:

#### 1. Semantic Tool Filtering (RAG)
- Query: "I'm cold" → Top 30 relevant tools injected
- LLM sees: turnOnAC, setTemperature, setSeatHeater, etc
- LLM doesn't see: navigate, playMusic (not relevant)

#### 2. System Prompt Examples
```
=== STRICT RULES ===

1. HVAC: If user asks to change temperature or is cold:
   Example: User: "I'm freezing"
   Assistant: <TOOL>setTemperature(68)</TOOL> I'm warming it up.

2. NAVIGATION: If user asks to go somewhere:
   Example: User: "Take me to downtown"
   Assistant: <TOOL>navigate(Downtown)</TOOL> Routing for you.
```

#### 3. Constraint Checking
- Before execution, system validates:
  - Is speed too high for windows?
  - Is permission granted?
  - Is value in valid range?

---

## HARDWARE INTEGRATION

### Q22: How does the system write to vehicle hardware?

**A:** Three-step hardware write process:

```
ToolManager detects: <TOOL>turnOnAC()</TOOL>
       ↓
Step 1: Find Tool Definition
  matchedTool = activeTools["turnOnAC"]
  propertyId = 354419973
  value = "true"
       ↓
Step 2: Call VehicleManager
  VehicleManager.setPropertyVerified(
    propertyId = 354419973,
    value = "true",
    dataType = "BOOLEAN"
  )
       ↓
Step 3: Hardware Bridge (VHAL)
  carPropertyManager.setBooleanProperty(
    propertyId = 354419973,
    areaId = 0,
    value = true
  )
       ↓
Hardware Execution:
  VHAL → CAN Bus → Vehicle ECU → Physical AC Module
       ↓
Result: AC actually turns ON in vehicle
```

---

### Q23: What is VHAL and why do we use it?

**A:** VHAL = **Vehicle Hardware Abstraction Layer**

**Purpose:** Abstract away vehicle-specific hardware differences

**How it works:**
```
VehicleEdgeAssistant (our app)
       ↓
Android Automotive Framework (CarPropertyManager)
       ↓
VHAL (Vehicle HAL / Hardware Abstraction Layer)
       ↓
OEM-Specific Implementation
       ↓
CAN Bus Protocol
       ↓
Physical Hardware (AC, Windows, Lights, etc)
```

**Benefits:**
- ✅ Single code path works on all Android Automotive vehicles
- ✅ OEM can customize at VHAL level
- ✅ No direct CAN bus access needed
- ✅ Hardware abstraction = safer

**Property IDs are VHAL IDs:**
- 354419973 = AC ON/OFF (standardized)
- 291504905 = EV Battery Level (standardized)
- 320865540 = Sunroof position (standardized)

---

### Q24: How does hardware verification work?

**A:** Four-step verification to ensure command actually executed:

```
Step 1: PRE-CHECK
  Is AC already ON?
  └─ If YES → Return immediately (avoid redundant write)
  └─ If NO → Continue

Step 2: REGISTER CALLBACK
  Create listener for property change event:
  carPropertyManager.registerCallback(
    propertyId = 354419973,
    listener = { onChangeEvent(value) }
  )

Step 3: ISSUE COMMAND
  carPropertyManager.setBooleanProperty(354419973, true)
  └─ Sends via CAN bus to vehicle

Step 4: WAIT FOR CONFIRMATION
  Timeout: 1500ms
  └─ If callback fires with new value = true → ✅ SUCCESS
  └─ If timeout expires → Retry 3 times:
     • Wait 500ms, try again
     • Wait 1000ms, try again
     • Wait 2000ms, try again
     • If all fail → ❌ RETURN ERROR
```

**Benefits:**
- ✅ Never assumes command executed
- ✅ Handles flaky CAN bus with retries
- ✅ User gets honest feedback
- ✅ Zero false confirmations

---

### Q25: What happens if hardware write fails?

**A:** Automatic retry with exponential backoff:

```
Attempt 1: Issue write
  └─ Wait for callback (1500ms timeout)
  └─ No response? → Retry

Attempt 2: Wait 500ms, then retry
  └─ Try again
  └─ No response? → Retry

Attempt 3: Wait 1000ms, then retry
  └─ Try again
  └─ No response? → Retry

Attempt 4: Wait 2000ms, then retry
  └─ Try again
  └─ No response? → Give up

Final Result:
  ❌ FAILED: "Hardware communication error. Please check your vehicle."
```

**User Gets:**
- ✓ Honest feedback (not false success)
- ✓ Attempts multiple times (handles transient issues)
- ✓ Clear error message
- ✓ No app crash

---

## PERFORMANCE & OPTIMIZATION

### Q26: What's the typical response time?

**A:** Complete timeline for "Turn on the AC":

```
T+0ms:      User speaks
T+100ms:    Speech-to-Text completes
T+250ms:    System prompt built (class + RAG + telemetry)
T+2300ms:   First LLM token (prefill phase)
T+2315ms:   Tool interception
T+2320ms:   Hardware write issued
T+2425ms:   VHAL confirmation received (✅ AC ON)
T+2430ms:   Continue remaining text to TTS
T+2700ms:   Audio synthesis
T+3200ms:   Audio plays on speaker

SUMMARY:
Speech → Action: 2.3 seconds ⚡
Speech → Audio Confirmation: 3.2 seconds 🎉
```

---

### Q27: How does the system optimize for speed?

**A:** Seven optimization techniques:

#### 1. Differential KV Cache
- First message: Full computation (2-3s)
- Follow-ups: Reuse cache (0.5-1.5s)

#### 2. Semantic Tool Filtering (RAG)
- Inject only 30 relevant tools (not all 50+)
- Reduces prompt size
- Faster LLM inference

#### 3. Hardware Delegate (GPU/NPU)
- LiteRT routes to GPU (Adreno)
- Falls back to NPU (Hexagon) or CPU (XNNPACK)
- Parallel processing = faster tokens

#### 4. Pre-computed Embeddings
- Tool embeddings computed once at startup
- Zero latency during queries
- Stored in memory

#### 5. Sentence-Boundary Streaming
- Don't wait for full response
- Push sentences to TTS immediately
- User hears first sentence while LLM still generating

#### 6. Streaming Output
- Process tokens as they arrive
- Don't buffer full response
- Real-time feedback to user

#### 7. Memory Efficiency
- Sliding window (max 500 chars history)
- Prevents KV cache explosion
- No GC pauses during token generation

---

### Q28: Can I use local models or only on-device?

**A:** Both! Architecture supports multiple backends:

#### Option 1: 100% Local (Recommended)
- Model: Gemma 2B, Qwen 1.5B, SmolLM 135M
- Location: On-device only
- Speed: 1.5-3.5s per query
- Privacy: 100% (no cloud)
- Cost: One-time (free LiteRT models)

#### Option 2: Cloud Fallback
- Models: Google Gemini, Anthropic Claude, OpenAI GPT
- Location: Sent to cloud services
- Speed: Faster (more powerful models)
- Privacy: Data sent to servers
- Cost: Per-request API fees

#### Option 3: Hybrid (Smart)
- Local model is default
- Cloud model as fallback
- Falls back if local inference fails
- Best of both worlds

**In LLMManager:**
```kotlin
try {
    response = localLLMEngine.inference(prompt)
} catch (e: Exception) {
    // Fallback to cloud
    response = geminiAPI.inference(prompt)
}
```

---

## SECURITY & SAFETY

### Q29: How is user privacy protected?

**A:** Multiple layers of privacy protection:

#### Layer 1: On-Device Processing
- ✅ Voice transcription: On-device (Vosk wakeword)
- ✅ LLM inference: On-device (LiteRT)
- ✅ Tool execution: On-device (VHAL)
- ✅ Zero data sent outside vehicle

#### Layer 2: Encrypted Communication
- HTTPS for cloud API calls (optional)
- TLS for any external communication
- No API keys exposed in logs

#### Layer 3: Data Deletion
- Conversation history deleted after sliding window truncation
- KV cache cleared on app restart
- User preferences stored locally only

#### Layer 4: Permission Checks
- Standard Android permissions (Microphone, Location)
- No access to user files or contacts
- Limited to vehicle hardware only

#### Layer 5: Audit Trail
- All hardware writes logged
- Tool executions tracked
- Failed attempts recorded (for debugging)

**Comparison with Cloud Assistants:**
```
Cloud Assistant (Google Assistant):
├─ Your voice recordings → Google servers ❌
├─ Conversation history → Cloud storage ❌
├─ Third-party integrations → Data sharing ❌
└─ Privacy: LOW

VehicleEdgeAssistant:
├─ Your voice → Stays in vehicle ✅
├─ Conversation → Deleted after use ✅
├─ No integrations → No data sharing ✅
└─ Privacy: MAXIMUM
```

---

### Q30: What are the safety constraints?

**A:** Four layers of safety:

#### Layer 1: JSON Constraints
```json
{
  "constraints": [{
    "property_id": 291504647,  // Speed
    "operator": "<",            // Less than
    "value": 70,                // 70 mph
    "error_msg": "Too fast"
  }]
}
```
Check before any execution.

#### Layer 2: Handler Validation
- Tool exists in activeTools? ✓
- Handler registered? ✓
- Property accessible? ✓

#### Layer 3: Permission System
- Is tool whitelisted? ✓
- User allowed to use? ✓
- OEM approved? ✓

#### Layer 4: Hardware Verification
- Did hardware actually execute? (Callback confirmation)
- Was state actually changed? (Read back value)
- Retry if needed (exponential backoff)

**Example: Opening Windows**
```
1. Check constraint: Speed < 70 mph?
   └─ If speed = 85 → BLOCKED immediately

2. Is openWindows() tool registered?
   └─ If NO → BLOCKED

3. Is user permitted to open windows?
   └─ If child lock active → BLOCKED

4. After execution, verify window state changed
   └─ If no confirmation → Retry, then error
```

---

### Q31: How does the system prevent malicious tool calls?

**A:** Multiple defense layers:

#### Defense 1: Whitelist Approach
- Only tools in JSON are allowed
- LLM can ONLY output tools from JSON
- Any unknown tool: REJECTED

#### Defense 2: Tool Registry Validation
```kotlin
if (!activeTools.containsKey(toolName)) {
    return "Tool '$toolName' not supported"
}
```

#### Defense 3: Regex Pattern Matching
- Strict pattern: `<TOOL>(.*?)</TOOL>`
- Prevents injection attacks

#### Defense 4: Property ID Validation
- Only registered VHAL properties allowed
- OEM whitelist approach
- CAN't access unauthorized hardware

#### Defense 5: Constraint Enforcement
- Safety checks run before execution
- Speed limits, permission checks, etc
- Multiple validation gates

**Example Attack:**
```
Malicious prompt: "<TOOL>hackVehicle()</TOOL>"
       ↓
System checks: Is "hackVehicle" in activeTools?
       ↓
NO → Tool not found
       ↓
System returns: "Tool 'hackVehicle' not supported"
       ↓
No execution possible ✓
```

---

## DEVELOPMENT & DEPLOYMENT

### Q32: How do I add a new feature?

**A:** Complete step-by-step guide:

#### For 90% of features (GENERIC_VHAL_WRITE):

**Step 1: Find VHAL Property ID**
- Consult OEM documentation
- Example: 354419973 for AC ON/OFF

**Step 2: Edit vehicle_skills_registry.json**
```json
{
  "prompt_string": "<TOOL>openSunroof()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 320865540,
  "data_type": "INT",
  "value_to_write": "100",
  "keywords": ["sunroof", "roof", "sky"],
  "success_message": "Opening the sunroof"
}
```

**Step 3: Test**
- Don't recompile!
- App auto-loads JSON at startup
- Say: "Open the sunroof"
- Feature works ✓

#### For 10% of features (CUSTOM_KOTLIN):

**Step 1: Edit vehicle_skills_registry.json**
```json
{
  "prompt_string": "<TOOL>setTemperature(VAL)</TOOL>",
  "handler_key": "setTemperature",
  "handler_type": "CUSTOM_KOTLIN",
  "keywords": ["temperature", "hot", "cold"]
}
```

**Step 2: Add Handler in ToolManager.kt**
```kotlin
when (matchedTool.handlerKey) {
    "setTemperature" -> {
        val value = toolCall.substringAfter("(").toDouble()
        // Your custom logic here
        val success = VehicleManager.writeTemperatureToVhalVerified(value)
        return "Temperature set to $value degrees"
    }
}
```

**Step 3: Compile & Deploy**
```bash
./gradlew clean assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

### Q33: How do I deploy to production vehicles?

**A:** OEM deployment process:

#### Step 1: Build Signed APK
```bash
./gradlew clean assembleRelease \
  -Pandroid.injected.signing.store.file=keystore.jks \
  -Pandroid.injected.signing.store.password=... \
  -Pandroid.injected.signing.key.alias=... \
  -Pandroid.injected.signing.key.password=...
```

#### Step 2: Privileged System App Installation
```bash
adb root
adb remount
adb push app/build/outputs/apk/release/app-release.apk /system/priv-app/VEA/
adb push privapp-permissions.xml /etc/permissions/
adb reboot
```

#### Step 3: OTA Model Distribution
- Package LLM model: gemma-2b-it.litertlm (2-3GB)
- Host on OEM servers
- Include in OTA updates
- Download on first launch

#### Step 4: Push Models to Device
```bash
adb push gemma-2b-it.litertlm /data/media/10/Android/data/com.example.vea/files/
adb push vehicle_skills_registry.json /data/media/10/Android/data/com.example.vea/assets/
```

---

### Q34: Can I update features without OTA?

**A:** **YES! This is the game-changer.**

**Traditional:**
```
New feature needed
  → Engineer writes code
  → Test on 10 devices
  → QA approval
  → Build APK
  → Upload to Play Store
  → Wait for approval
  → Users install update
  → 2-4 weeks ⏰
```

**VehicleEdgeAssistant:**
```
New feature needed
  → Edit vehicle_skills_registry.json
  → Upload file to vehicle (via Telematics)
  → Vehicle restarts app
  → Feature works immediately ✓
  → 5 minutes ⚡
```

**No code changes, no compilation, no testing needed!**

---

## TROUBLESHOOTING

### Q35: Tool isn't executing, what do I check?

**A:** Debugging checklist:

```
1️⃣  Is tool in JSON?
    ├─ Check: vehicle_skills_registry.json contains tool?
    ├─ If NO → Add it
    └─ If YES → Continue

2️⃣  Is LLM outputting the exact prompt_string?
    ├─ Check: LLM must output EXACTLY "<TOOL>commandName()</TOOL>"
    ├─ Example: "<TOOL>turnOnAC()</TOOL>" (correct)
    ├─ Wrong: "<TOOL> turnOnAC() </TOOL>" (space = no match)
    └─ Enable logcat: adb logcat | grep "ToolManager"

3️⃣  Is tool in activeTools map?
    ├─ Check ToolManager logs at startup
    ├─ Should see: "Loaded 50 tools from JSON"
    └─ Verify tool appears in list

4️⃣  Are constraints passing?
    ├─ Check: Is speed too high? (window example)
    ├─ Check: Are permissions OK?
    ├─ Logcat: Look for "Constraint failed" error
    └─ Temporarily remove constraints to test

5️⃣  Is handler_type correct?
    ├─ GENERIC_VHAL_WRITE: Check property_id and data_type
    ├─ CUSTOM_KOTLIN: Check handler_key registered in when()
    └─ Logcat: Look for handler routing logs

6️⃣  Is VHAL property ID correct?
    ├─ Wrong ID → No execution
    ├─ Verify with OEM documentation
    ├─ Logcat: "Property not found" error
    └─ Try reading property first: adb shell getprop | grep property_id

7️⃣  Check VHAL callback received?
    ├─ Logcat: Look for "onChangeEvent" fired
    ├─ If not firing → Hardware layer issue
    ├─ Verify carPropertyManager initialized?
    └─ Check Android Automotive permissions
```

---

### Q36: LLM is responding very slowly, how to optimize?

**A:** Performance troubleshooting:

```
1️⃣  Check First Token Latency (TTFT)
    ├─ Measure: Time from prompt send to first token
    ├─ If > 5 seconds → Model too large for hardware
    ├─ Options:
    │   • Switch to smaller model (SmolLM 135M)
    │   • Check GPU is being used: adb shell dumpsys SurfaceFlinger
    │   • Disable other apps (memory pressure)
    └─ Expected: 1.5-3.5 seconds

2️⃣  Check prompt size
    ├─ Very large prompts → slow inference
    ├─ Check: How many tools injected?
    ├─ Tools should be ~30 (via RAG filtering)
    ├─ If > 50 → RAG not working
    └─ Check SemanticSearchManager logs

3️⃣  Check KV cache
    ├─ First message: Slower (full computation)
    ├─ Follow-up: Should be 2x faster (reused cache)
    ├─ If follow-ups also slow → KV cache not working
    ├─ Check: isFirstMessage flag
    └─ Logcat: Look for "KV cache" logs

4️⃣  Check GPU availability
    ├─ Verify GPU delegate loaded
    ├─ Command: adb shell dumpsys SurfaceFlinger
    ├─ Look for: GPU utilization > 50%
    ├─ If not using GPU → Falling back to CPU
    └─ CPU = much slower

5️⃣  Check model size
    ├─ Gemma 2B → ~3.5s TTFT
    ├─ Qwen 1.5B → ~2.5s TTFT
    ├─ SmolLM 135M → ~1.0s TTFT
    ├─ Use smaller model if too slow
    └─ Tradeoff: Smaller = faster but less intelligent
```

---

### Q37: Hardware not responding to commands

**A:** VHAL communication troubleshooting:

```
1️⃣  Check VHAL permission
    ├─ Is app installed as /system/priv-app?
    ├─ Command: adb shell pm list packages | grep vea
    ├─ If NOT in priv-app → Add android.car.permission.* required
    └─ May need to remount: adb root && adb remount

2️⃣  Check CarPropertyManager initialized
    ├─ Logcat: "VehicleManager initialized" at startup?
    ├─ If NO → Initialization failed
    ├─ Check: Is Android Automotive OS available?
    ├─ Try: adb shell getprop ro.hardware
    └─ Should contain "automotive"

3️⃣  Check property ID
    ├─ Is property valid for this vehicle?
    ├─ Verify OEM documentation
    ├─ Try reading property first: adb shell getprop
    ├─ If not listed → Property not available
    └─ Try different property ID

4️⃣  Check CAN bus / ECU communication
    ├─ CAN bus timeout → No VHAL callback
    ├─ Logcat: "VHAL write timeout" error
    ├─ Hardware might be disconnected
    ├─ Try restarting vehicle
    └─ If persistent → Hardware issue

5️⃣  Check retry logic
    ├─ System retries 3 times with backoff
    ├─ Logcat: Should show retries
    ├─ If all retries fail → Return error to user
    ├─ User gets: "Hardware communication error"
    └─ Check if error message appears in UI

6️⃣  Enable debugging
    ├─ Add verbose logs: adb logcat | grep "VehicleManager\|ToolManager"
    ├─ Monitor property changes: adb shell dumpsys car_service
    ├─ Watch for callbacks and errors
    └─ Identify exact failure point
```

---

### Q38: Semantic search not filtering tools correctly

**A:** RAG debugging:

```
1️⃣  Verify embeddings computed
    ├─ Logcat: "Built tool embeddings cache" message?
    ├─ Should show: "Total tools embedded: XX"
    ├─ If 0 → Embeddings failed to compute
    └─ Check: Universal Sentence Encoder model loaded?

2️⃣  Check query embedding
    ├─ Does query embed to vector?
    ├─ Logcat: "Query embedding" logs
    ├─ If NULL → Embedding failed
    └─ Check: embedText() method

3️⃣  Verify cosine similarity scores
    ├─ Logcat: Show similarity scores for each tool
    ├─ High match (0.8-1.0) → Should be top 30
    ├─ Low match (0.1-0.3) → Should be filtered out
    ├─ If all scores same → Cosine similarity broken
    └─ Check: math in cosineSimilarity() method

4️⃣  Check top-K filtering
    ├─ System should return exactly 30 tools
    ├─ Logcat: "Returning top 30 tools"
    ├─ If not 30 → Check: take(topK) in search()
    └─ Verify sorting by descending similarity

5️⃣  Test RAG manually
    ├─ User: "I'm cold"
    ├─ Should get: setTemperature, setSeatHeater, turnOnAC
    ├─ Should NOT get: navigate, playMusic
    ├─ Logcat: See actual similarities
    ├─ If wrong tools → Embeddings or keywords problem
    └─ Check: keywords field in JSON tools
```

---

### Q39: Conversation memory not working

**A:** Memory management debugging:

```
1️⃣  Check MemoryManager initialized
    ├─ Logcat: "MemoryManager initialized"?
    ├─ If not in logs → Never initialized
    └─ Verify: LLMManager.initialize() called

2️⃣  Verify turns being added
    ├─ After user query → Should add turn to conversationHistory
    ├─ Logcat: "Added turn: User. Total turns: X"
    ├─ If no logs → addTurn() not called
    └─ Check: AssistantSession calls MemoryManager.addTurn()

3️⃣  Check sliding window
    ├─ After adding turn → Should check size
    ├─ Logcat: "Sliding window threshold reached..."?
    ├─ Should see old turns deleted
    ├─ If maxChars exceeded without deletion → Bug
    └─ Check: getSlidingWindowContext() logic

4️⃣  Verify context injected into prompt
    ├─ Long conversation (10+ turns)?
    ├─ Should only keep last N turns (~500 chars)
    ├─ Check LLM system prompt
    ├─ Should see "Memory: User: ..."
    ├─ If blank → getSlidingWindowContext() returns empty
    └─ Debug: Check maxChars parameter

5️⃣  Test memory across turns
    ├─ Turn 1: "My name is John" → LLM remembers
    ├─ Turn 2: "What's my name?" → Should say "John"
    ├─ If forgets → Memory not injected into prompt
    ├─ Logcat: Check MemoryManager calls
    └─ Verify: Memory context in system prompt
```

---

### Q40: App crashes or out of memory

**A:** Stability troubleshooting:

```
1️⃣  Check logcat for crash
    ├─ Command: adb logcat | grep FATAL\|crash\|Exception
    ├─ Look for: OutOfMemoryError, NullPointerException
    ├─ Get full stack trace
    └─ Pin exact line causing crash

2️⃣  Out of Memory: KV Cache too large
    ├─ Many turns → KV cache grows
    ├─ Should be bounded by sliding window
    ├─ If crashes on long conversation → Window not truncating
    ├─ Check: MemoryManager.getSlidingWindowContext()
    └─ Solution: Lower maxChars parameter

3️⃣  Out of Memory: Model too large
    ├─ Device RAM < model size
    ├─ Gemma 2B needs ~3-4GB RAM
    ├─ Check: Device specs via adb shell getprop
    ├─ Solution: Use smaller model (SmolLM 135M = 150MB)
    └─ Check free memory: adb shell dumpsys meminfo

4️⃣  Null Pointer Exception
    ├─ Logcat shows: NullPointerException at ClassName:lineNumber
    ├─ Usually: activeTools not initialized
    ├─ Or: ToolManager.initialize() not called
    ├─ Check: LLMManager.initialize() called on startup
    └─ Verify: All singletons initialized in order

5️⃣  Callback Memory Leak
    ├─ VehicleManager registers VHAL callbacks
    ├─ Must unregister when app dies
    ├─ Check: onDestroy() unregisters callbacks
    ├─ If not → Memory leak, eventually crashes
    └─ Solution: Add unregister in onPause/onDestroy

6️⃣  Long-running conversations
    ├─ After 50+ turns → Memory grows
    ├─ Should truncate old turns
    ├─ If not → Eventually crashes
    ├─ Check: Sliding window working?
    ├─ Test: 100 turns → Should still work
    └─ If crashes → Window not truncating correctly
```

---

## SUMMARY TABLE

```
COMPONENT          RESPONSIBILITY             KEY METHOD
─────────────────────────────────────────────────────────────────
LLMManager         LLM orchestration          getDefaultSystemPrompt()
ToolManager        Tool execution             executeToolCall()
VehicleManager     Hardware bridge            setPropertyVerified()
SemanticSearch     RAG filtering              search()
MemoryManager      Conversation history       getSlidingWindowContext()
AssistantSession   Voice overlay UI           Token stream processing

HANDLER TYPES      USE CASE                   COMPLEXITY
─────────────────────────────────────────────────────────────────
GENERIC_VHAL_WRITE Simple ON/OFF, direct HW   90% of tools, No code
CUSTOM_KOTLIN      Complex logic, conversions 10% of tools, Code needed

SAFETY LAYERS      PURPOSE
─────────────────────────────────────────────────────────────────
JSON Constraints   Speed, permission checks
Handler Validation Tool registry check
Permission System  User allowed?
Hardware Verify    Did it actually execute?

TIMING BREAKDOWN   TYPICAL DURATION
─────────────────────────────────────────────────────────────────
STT                ~100ms
Prompt building    ~150ms
LLM inference      ~2100ms (first token)
Tool execution     ~100ms
TTS                ~200ms
Audio playback     ~500ms
─────────────────────────────────────────────────────────────────
TOTAL              ~3200ms (3.2 seconds)
```

---

**End of FAQ - All questions answered comprehensively!**

For more details, see:
- `SYSTEM_ARCHITECTURE_DEEP_DIVE.md` - Deep technical dive
- `SYSTEM_ARCHITECTURE_DIAGRAMS.md` - Visual flow diagrams
- `ARCHITECTURE.md` - Original architecture documentation
- `README.md` - Getting started guide

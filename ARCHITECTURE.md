# Automotive AI Assistant Architecture

This document outlines the architecture, components, and data flow of the Android Automotive AI Assistant. The application is designed to provide ultra-low latency, completely offline, LLM-powered voice assistance with deep, dynamic integration into the Android Automotive OS (AAOS) VHAL (Vehicle Hardware Abstraction Layer).

## Core Architecture Overview

The system is built on a split architecture: a primary User Interface (`LocalLLMActivity`) for configuration and extended interactions, and a lightweight System UI Overlay (`AssistantSession`) for persistent, system-wide access. Both rely on a shared Singleton inference engine (`LLMManager`) to ensure rapid response times and consistent state.

### High-Level Component Diagram

```mermaid
flowchart TD
    %% Professional Enterprise Theme
    classDef sysApp fill:#1E293B,stroke:#334155,stroke-width:2px,color:#F8FAFC,rx:8px,ry:8px;
    classDef aospAPI fill:#0EA5E9,stroke:#0284C7,stroke-width:2px,color:#FFFFFF,rx:8px,ry:8px,font-weight:bold;
    classDef logic fill:#8B5CF6,stroke:#7C3AED,stroke-width:2px,color:#FFFFFF,rx:8px,ry:8px,font-weight:bold;
    classDef ai fill:#10B981,stroke:#059669,stroke-width:2px,color:#FFFFFF,rx:8px,ry:8px,font-weight:bold;
    classDef hardware fill:#F59E0B,stroke:#D97706,stroke-width:2px,color:#FFFFFF,rx:8px,ry:8px,font-weight:bold;
    classDef config fill:#64748B,stroke:#475569,stroke-width:2px,color:#FFFFFF,rx:8px,ry:8px,font-weight:bold;

    subgraph Input ["1. User Input & AOSP Audio Framework"]
        MIC([🎙️ Microphone])
        TXT([⌨️ Keyboard])
        STT["🎤 android.speech.SpeechRecognizer<br/>(Cloud / On-Device)"]:::aospAPI
        VOSK["🗣️ Vosk WakeWord<br/>(Offline Acoustic Model)"]:::sysApp
    end

    subgraph AppUI ["2. System UI Application Layer"]
        SESSION["📱 AssistantSession<br/>(Voice Overlay Window)"]:::sysApp
        ACT["📱 LocalLLMActivity<br/>(Configuration UI)"]:::sysApp
    end

    subgraph Orchestration ["3. Core AI Orchestration (Kotlin Singleton)"]
        LLM{"🧠 LLMManager<br/>(Prompt & State Control)"}:::logic
        TM["🛠️ ToolManager<br/>(Semantic Router & RAG)"]:::logic
        MEM["🧠 MemoryManager<br/>(Short-Term Context Window)"]:::logic
        JSON[("📄 custom_properties.json<br/>(Zero-Code Definitions)")]:::config
        PREFS[("💾 SharedPreferences<br/>(Long-Term User Memory)")]:::config
    end

    subgraph Inference ["4. ML Edge Execution (C++ / GPU / NPU)"]
        LITERT["⚙️ Google LiteRT Engine<br/>(liblitertlm_jni.so)"]:::ai
        LOCAL[/"📱 Local LLMs<br/>(Gemma, Qwen)"/]:::ai
        CLOUD[/"☁️ Cloud APIs<br/>(Gemini, Claude)"/]:::ai
    end

    subgraph Output ["5. AOSP Output & System Execution"]
        TTS["🔊 android.speech.tts.TextToSpeech<br/>(Audio Feedback)"]:::aospAPI
        CPM["⚙️ android.car.hardware.property.CarPropertyManager<br/>(Vehicle API)"]:::aospAPI
        CARSERVICE["🛠️ com.android.car.CarService<br/>(Binder IPC)"]:::aospAPI
        VHAL["🌉 Vehicle HAL<br/>(Hardware Abstraction)"]:::hardware
        CAN["🚗 CAN Bus / Physical ECUs"]:::hardware
        SPK([🔈 Speakers])
        INTENTS["📱 Android Framework<br/>(ActivityManager, AudioManager, MediaBrowser)"]:::aospAPI
        MEDIA["🎵 Media & Browser Apps<br/>(ACTION_VIEW, KEYCODE_MEDIA_*)"]:::sysApp
        PHONE["📞 Telecom App<br/>(ACTION_DIAL)"]:::sysApp
    end

    %% Flow Mapping
    MIC -->|Audio Stream| STT
    MIC -->|Continuous Stream| VOSK
    VOSK -->|"Hey Auto" Trigger| SESSION
    STT -->|Transcribed Text| SESSION
    TXT -->|Raw String| ACT
    
    SESSION <==>|User Query| MEM
    ACT <==>|User Query| MEM
    MEM <==>|Context Window| LLM
    
    JSON -.->|Injects Available Tools| TM
    JSON -.->|Maps VHAL IDs| CPM
    
    PREFS <==>|Reads/Writes Preferences| TM
    PREFS -.->|Injects Memory| LLM
    
    LLM ==>|"Context + Tools + History"| LITERT
    LITERT -->|"Hardware Delegate"| LOCAL
    LITERT -->|"SSE Socket"| CLOUD
    
    LOCAL & CLOUD -->|"Token Stream"| LITERT
    LITERT ==>|"Response Stream"| LLM
    
    LLM -->|"Cleaned Text Stream"| TTS
    TTS -->|"Synthesized Audio"| SPK
    
    LLM -->|"<TOOL> Execution Tag"| TM
    TM -->|"VHAL Payload"| CPM
    CPM -->|"Cross-Process IPC"| CARSERVICE
    CARSERVICE -->|"HIDL / AIDL"| VHAL
    VHAL -->|"Electrical Actuation"| CAN

    TM -->|"Standard Android Intent"| INTENTS
    INTENTS -->|"Launch & Media Routing"| MEDIA
    INTENTS -->|"Launch Dialer"| PHONE
```

### AOSP Integration Stack Diagram

The following diagram illustrates exactly where the LLM Engine Orchestrator resides within the Android Open Source Project (AOSP) architecture stack, and how natural language commands are translated down to the vehicle hardware via the Car API and Vehicle HAL.

```mermaid
flowchart TD
    %% Professional Styling
    classDef app fill:#0F172A,stroke:#38BDF8,stroke-width:3px,color:#F8FAFC,rx:8px,ry:8px,font-weight:bold;
    classDef api fill:#1E293B,stroke:#94A3B8,stroke-width:2px,color:#CBD5E1,rx:8px,ry:8px;
    classDef framework fill:#1E293B,stroke:#A78BFA,stroke-width:2px,color:#E6D5B8,rx:8px,ry:8px;
    classDef hal fill:#1E293B,stroke:#34D399,stroke-width:2px,color:#A7F3D0,rx:8px,ry:8px;
    classDef hw fill:#1E293B,stroke:#FB923C,stroke-width:2px,color:#FED7AA,rx:8px,ry:8px;

    APP_LLM["🚀 AI System Apps<br/>(LocalLLMActivity, ToolManager)"]:::app
    
    CPM["⚙️ Car API Layer<br/>(CarPropertyManager)"]:::api
    
    CS["🛠️ Android Framework<br/>(com.android.car.CarService)"]:::framework
    
    AM["📱 Android Framework<br/>(ActivityManager, AudioManager)"]:::framework
    
    VHAL["🌉 Hardware Abstraction<br/>(Vehicle HAL)"]:::hal
    
    CAN["🚗 Hardware Layer<br/>(CAN Bus & ECUs)"]:::hw
    
    SYSTEM_APPS["🎵 System Apps<br/>(Media, Dialer, Browser)"]:::app

    %% Data flow mapping
    APP_LLM ==>|"1a. Tool Execution (Write) / Telemetry (Read)"| CPM
    CPM ==>|"2a. Cross-Process Binder IPC"| CS
    CS ==>|"3a. Hardware Interface (HIDL / AIDL)"| VHAL
    VHAL ==>|"4a. Raw CAN Payload / Electrical Signals"| CAN
    
    APP_LLM ==>|"1b. Standard Intents (Play, Pause, Call)"| AM
    AM ==>|"2b. Dispatch Intent / Media Key Event"| SYSTEM_APPS
```

### Vertical Layer Stack

For a simplified view of the system architecture from a pure software-stack perspective:

```mermaid
flowchart TD
    classDef app fill:#0F172A,stroke:#38BDF8,stroke-width:2px,color:#F8FAFC,rx:8px,ry:8px,font-weight:bold;
    classDef fw fill:#0F172A,stroke:#A78BFA,stroke-width:2px,color:#F8FAFC,rx:8px,ry:8px,font-weight:bold;
    classDef hal fill:#0F172A,stroke:#34D399,stroke-width:2px,color:#F8FAFC,rx:8px,ry:8px,font-weight:bold;
    classDef hw fill:#0F172A,stroke:#FB923C,stroke-width:2px,color:#F8FAFC,rx:8px,ry:8px,font-weight:bold;

    APP["📱 1. Application Layer<br/>(LocalLLMActivity, ToolManager)"]:::app
    FW["🛠️ 2. Framework Layer<br/>(CarPropertyManager, ActivityManager)"]:::fw
    HAL["🌉 3. Hardware Abstraction<br/>(Vehicle HAL - VHAL)"]:::hal
    HW["🚗 4. Hardware Layer<br/>(CAN Bus, ECUs)"]:::hw
    SYS_APP["📱 OS Applications<br/>(Media, Dialer, Apps)"]:::app

    APP ==>|"Binder IPC"| FW
    FW ==>|"HIDL / AIDL"| HAL
    HAL ==>|"Electrical"| HW
    FW ==>|"Intents"| SYS_APP
```

### Class & Structural Block Diagram

The following diagram illustrates the class relationships, dependencies, and interfaces between the core components of the application.

```mermaid
classDiagram
    class LocalLLMActivity {
        +initLlm()
        -executeToolCall(toolCall)
    }

    class AssistantSession {
        +onShow(args, showFlags)
        -handleQuery(query)
    }

    class LLMManager {
        <<Singleton>>
        -LlmInference engine
        +initialize()
        +getSystemPrompt()
    }

    class ToolManager {
        <<Singleton>>
        +executeToolCall()
        -dispatchIntent()
        -dispatchMediaKeyEvent()
    }

    class MemoryManager {
        <<Singleton>>
        +addTurn()
        +getSlidingWindowContext()
    }

    class VehicleManager {
        <<Singleton>>
        +getSensorContext()
        +writeProperty()
    }

    class AndroidFramework {
        <<System>>
        +ActivityManager
        +AudioManager
        +MediaBrowserService
    }

    class custom_properties_json {
        <<Zero-Code Config>>
        +properties: Array
        +tools: Array
    }
    
    %% Relationships
    LocalLLMActivity ..> LLMManager : Initializes
    AssistantSession ..> MemoryManager : Logs Query
    LocalLLMActivity ..> MemoryManager : Logs Query
    MemoryManager --> LLMManager : Feeds Context
    AssistantSession ..> LLMManager : Queries
    LocalLLMActivity ..> ToolManager : Executes Tools
    AssistantSession ..> ToolManager : Executes Tools
    ToolManager --> VehicleManager : Actuates Hardware (VHAL)
    ToolManager --> AndroidFramework : Dispatches Intents (Media/Phone)
    LLMManager --> VehicleManager : Reads Telemetry
    custom_properties_json ..> ToolManager : Parsed dynamically
    custom_properties_json ..> VehicleManager : Parsed dynamically
```

## Zero-Code Dynamic AOSP Property & Tool Handling

One of the most powerful features of this architecture is its **JSON-driven Zero-Code engine**. The application dynamically handles AOSP (Android Open Source Project) and OEM-specific Vendor Properties without requiring you to write or compile Kotlin code for every new feature.

At startup, the `ToolManager` and `VehicleManager` parse the `assets/custom_properties.json` file. This file acts as the bridge between the LLM's natural language capabilities and the car's native VHAL network.

### 1. Dynamic Sensor Reading (Properties)
When the LLM needs context (e.g., "What is the battery level?"), `VehicleManager` automatically iterates through all objects defined in the `"properties"` JSON array. It subscribes to these `property_id` values via the `CarPropertyManager` and dynamically translates them into human-readable strings injected into the LLM's System Prompt.

**Example: AOSP vs Vendor Properties**
- **AOSP Standard:** `291504905` (EV Battery Level). Android natively knows what this is.
- **Vendor Custom:** `555000123` (OEM Custom Sensor). Even though Android doesn't natively map this ID to a name, the app will blindly read it and pass the raw `FLOAT`/`INT` to the LLM based on how you define it in the JSON.

### 2. Dynamic Hardware Actuation (Tools)
When the LLM generates a tool call (e.g., `<TOOL>setAmbientLight(5)</TOOL>`), the `ToolManager` cross-references the command against the `"tools"` JSON array.
If it finds a matching `GENERIC_VHAL_WRITE` handler, the `ToolManager` uses reflection to securely inject the specified data payload directly into the target `property_id` on the CAN bus via `CarPropertyManager.setProperty()`.

---

## How to Add a New Action and Property

Adding a new feature to the assistant is a simple two-step process using `custom_properties.json` and, optionally, `LLMManager.kt` if you want to explicitly guide the AI's behavior.

### Scenario: Adding Control for the Sunroof

#### Step 1: Update `custom_properties.json`
To give the AI the ability to open the sunroof, define a new tool in the JSON array. You need the exact AOSP or Vendor VHAL Property ID (e.g., `320865540` for `WINDOW_POS`).

```json
{
  "prompt_string": "<TOOL>openSunroof()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 320865540,
  "data_type": "INT",
  "area_id": 16, 
  "value_to_write": "100",
  "success_message": "Opening the sunroof now."
}
```
* **`prompt_string`**: The exact syntax you want the LLM to output.
* **`handler_type`**: `GENERIC_VHAL_WRITE` tells the `ToolManager` to bypass custom Kotlin logic and automatically write the value to the VHAL.
* **`property_id`**: The target VHAL ID.
* **`area_id`**: The specific zone (e.g., `16` represents the roof zone in AOSP).
* **`value_to_write`**: The payload (e.g., `100` means 100% open).

#### Step 2: (Optional) Guide the AI in `LLMManager.kt`
While the JSON injects the tool definition into the prompt automatically, you can add a strict rule in `LLMManager.getSystemPrompt()` to ensure the LLM understands *when* to use it, especially if the prompt length is constrained.

Open `LLMManager.kt` and locate the `getSystemPrompt()` method. Add a new contextual rule:

```kotlin
val isSunroof = q.contains("sunroof") || q.contains("roof") || q.contains("sky")

if (isSunroof || q.isEmpty()) {
    basePrompt.append("8. SUNROOF: If the user asks to open the sunroof or see the sky, you MUST output the EXACT syntax <TOOL>openSunroof()</TOOL>.\n")
}
```

That's it! Restart the app, and the AI will now securely actuate the sunroof when asked, without you needing to manually implement `CarPropertyManager` callbacks.

## Main Components Detail

### 1. LiteRT (LiteRT-LM) Integration Deep Dive
The core local intelligence of the application is powered by **Google's LiteRT** (formerly TensorFlow Lite), specifically the `com.google.ai.edge.litertlm` library.

- **C++ JNI Bridge**: The Kotlin `LLMManager` acts as a thin wrapper over the underlying C++ LiteRT engine (`liblitertlm_jni.so`). This ensures that the heavy matrix multiplication required for LLM inference happens natively, bypassing the Android Dalvik Virtual Machine for maximum performance.
- **Hardware Delegates**: LiteRT dynamically routes subgraphs of the neural network to the most efficient hardware available:
  - **GPU Delegate (OpenCL/OpenGL)**: The default backend. It compiles shader programs on the first run to execute LLM operations in parallel on the Adreno GPU.
  - **CPU/XNNPACK**: Used as a fallback or for models not fully supported by the GPU. It utilizes highly optimized ARM NEON vector instructions.
  - **NPU (Hexagon DSP)**: For specific Qualcomm-optimized models, LiteRT uses FastRPC (`libcdsprpc.so`) to offload INT4/INT8 quantized graphs directly to the Hexagon NPU for extreme power efficiency.
- **Native KV Caching**: LiteRT maintains the Key-Value (KV) cache persistently in native RAM (C++ space) rather than JVM memory. This prevents Android Garbage Collection pauses during token generation and allows rapid multi-turn conversations without needing to recompute the entire prompt history.

### 2. `LLMManager.kt` (Orchestration Engine)
- **Role**: Manages the `LiteRT` (`litertlm`) execution for on-device inference (e.g. Gemma, Qwen). Uses a native persistent Key-Value (KV) cache to maintain conversation history across multi-turn interactions.
- **Dynamic Context**: Constructs the System Prompt by combining the base persona, live `VehicleManager` sensor strings, and the dynamically loaded `ToolManager` JSON constraints.

### 2. `ToolManager.kt` (Execution Engine)
- **Role**: Parses the JSON configuration file (`custom_properties.json`) into active `ToolDefinition` objects.
- **RAG Execution**: Employs a semantic similarity engine. When the user asks a question, `ToolManager.getLlmToolsPrompt(query)` filters the available tools based on relevance to ensure the LLM is not overwhelmed with irrelevant tools, improving Time-To-First-Token (TTFT) latency.
- **Regex Interception**: Evaluates the LLM's streaming token output using a `<TOOL>(.*?)</TOOL>` Regex. If a match is found, it strips it from the UI, executes the JSON-defined action, and pushes the `success_message` to the TTS buffer.

### 3. `VehicleManager.kt` (Hardware Bridge)
- **Role**: Connects directly to the Android Automotive `CarPropertyManager`.
- **Read**: Translates raw VHAL float/int arrays (like `22.5` from `HVAC_TEMPERATURE_SET`) into human-readable strings (e.g., "The Driver side temperature is 22.5 degrees") and exposes them via `getSensorContext()`.
- **Write**: Translates `ToolManager` intents into hardware actuation via `carPropertyManager.setProperty(PropertyId, AreaId, Value)`.

### 4. `AssistantSession.kt` & `WakeWordService.kt` (UI and Audio)
- **WakeWordService**: Uses `Vosk` (Offline Acoustic Models) to constantly listen for "Hey Auto" without draining the battery or emitting system "beeps".
- **AssistantSession**: The transparent bottom-sheet overlay. It orchestrates the Android `SpeechRecognizer` for transcription, pushes the text to `LLMManager`, and streams the resulting tokens through a sentence-boundary regex to Android `TextToSpeech` (`TTS`).

## AOSP Framework Dependencies

While the diagrams highlight the major flow, this project heavily relies on several native Android Open Source Project (AOSP) framework components to achieve deep system integration without requiring root access:

1. **`android.service.voice.VoiceInteractionService`**: This is the core pillar of the UI. By extending this service, the application registers itself as the default OS Assistant. This grants it the power to be invoked globally via the physical steering wheel microphone button, hotwords, or the system navigation bar, drawing a `VoiceInteractionSession` overlay on top of any active app (like Google Maps) without dismissing it.
2. **`android.content.SharedPreferences`**: Used as a persistent, lightweight memory bank. When the LLM triggers the `<TOOL>remember()</TOOL>` action, the `ToolManager` writes the user's preferences here. This context is subsequently injected into the system prompt on boot.
3. **`android.media.browse.MediaBrowser` & `MediaController`**: For deep audio integration, the `ToolManager` uses these APIs to bind to active media sessions (like Spotify or YouTube Music) in the background. This allows the assistant to pause, play, or skip tracks seamlessly without having to visually launch the music app via standard intents.
4. **`android.speech.SpeechRecognizer` & `TextToSpeech`**: The project strictly uses the native on-device implementations of these APIs. This ensures zero-latency audio processing and guarantees that the user's voice data never leaves the vehicle.
5. **Android `<queries>` & Standard Intents**: The `AndroidManifest.xml` explicitly defines package visibility queries for `android.media.action.MEDIA_PLAY_FROM_SEARCH`, `geo:`, and `org.chromium.webview_shell`. This is required in modern Android (API 30+) to allow the `ToolManager`'s `ACTION_VIEW` and `ACTION_DIAL` intents to successfully resolve and launch third-party applications.

## Performance & Latency Optimizations

To achieve highly responsive execution on an automotive SoC, the architecture implements several aggressive latency optimizations:

### 1. Differential KV Cache Preservation (Local Models)
Originally, injecting the massive 1,500-token System Prompt (containing all the vehicle rules and tools) forced the LiteRT engine to recompute the entire context window, causing 3-4 second delays on every turn. 
**Optimization**: The massive System Prompt is now only injected on the **first turn** (Prefill phase). For all follow-up questions, the app only passes the delta (the new user query). The LiteRT Key-Value (KV) cache holds the original prompt in native RAM, dropping follow-up response times (TTFT) to **sub-second levels**.

### 2. Socket-Level SSE Streaming (Cloud Models)
**Optimization**: The `GeminiManager` bypasses standard synchronous HTTP calls by utilizing **Server-Sent Events (SSE)**. The app reads the raw TCP socket buffer in real-time, displaying words on the screen the exact millisecond they leave the server, dropping cloud latency to **< 1.5s**.

### 3. Semantic Tool RAG Filtering
**Optimization**: To prevent system prompt bloat, the `ToolManager` dynamically filters out irrelevant tools based on the user's query. By only injecting contextually relevant tools into the prompt, the token count is significantly reduced, directly accelerating the prefill compute time.

### 4. Sentence-Boundary TTS Streaming (Perceived Latency)
**Optimization**: A custom regex-based streaming chunker intercepts the LLM output. As soon as the LLM generates a punctuation mark (`.` or `?`), that single sentence is instantly pushed to the Android `TextToSpeech` engine. The car starts talking to the driver while the LLM is still quietly generating the rest of the response in the background, making the interaction feel instantaneous.

### Current TTFT Metrics
*   **Cloud APIs**: `< 1.5s` (SSE Streaming)
*   **Entry-Level Local** (e.g., SmolLM 135M): `< 1.0s` (GPU/CPU)
*   **Mid-Range Local** (e.g., Qwen 2.5 1.5B): `1.5s - 2.5s` (GPU)
*   **Premium Local** (e.g., Gemma 4 E2B): `2.5s - 3.5s` (GPU/NPU)

## Future Scaling Plan

As the underlying automotive hardware and edge-AI models evolve, the architecture is designed to scale in the following directions:

### 1. Agentic Workflows & Multi-Step Reasoning
Currently, the assistant operates on a direct Request-Response paradigm. Future scaling will introduce **Agentic loops** (e.g., ReAct). When given a high-level command like *"Prep the car for a road trip"*, the LLM will recursively break this down into multiple hidden steps: check fuel -> read tire pressure -> set climate control -> plot navigation, executing and verifying each tool before speaking to the user.

### 2. Multi-Modal Cabin Awareness (Vision & Audio)
Expanding the `VehicleManager` inputs to include multi-modal data. By feeding in-cabin camera frames (via `Camera2 API`) and microphone sentiment analysis into vision-capable edge models (like Gemini Nano Multi-modal), the assistant will be able to read driver fatigue, recognize passengers, and answer visual questions (e.g., *"Did I leave my bag in the back seat?"*).

### 3. Local Vector Database (Advanced RAG)
Replacing the current static tool array with an on-device embedded Vector Database (e.g., SQLite-vec or FAISS ported via JNI). This will allow the manufacturer to embed the entire 500-page Vehicle Owner's Manual as vector embeddings. The assistant will dynamically retrieve highly specific repair, maintenance, and dashboard warning light information completely offline.

### 4. Edge Fine-Tuning (LoRA Adapters)
Instead of relying on a monolithic base model, future versions will utilize **Low-Rank Adaptation (LoRA)** loading. The vehicle can dynamically swap small (20MB) LoRA weights at runtime to radically change the assistant's persona, support regional dialects, or specialize in specific automotive domains (e.g., loading an Off-Road LoRA when 4x4 mode is engaged).

### 5. Advanced NPU/DSP Memory Management
To push Time-To-First-Token (TTFT) latency even lower (sub-500ms), future architecture scaling will involve migrating the massive LLM Key-Value (KV) cache directly into the ultra-fast Hexagon DSP memory or EdgeTPU SRAM, bypassing standard Android RAM constraints and eliminating prompt-processing bottlenecks entirely.

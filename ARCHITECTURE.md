# Automotive AI Assistant Architecture

This document outlines the architecture, components, and data flow of the Android Automotive AI Assistant. The application is designed to provide ultra-low latency, completely offline, LLM-powered voice assistance with deep integration into Android Automotive OS (AAOS) VHAL (Vehicle Hardware Abstraction Layer).

## Core Architecture Overview

The system is built on a split architecture: a primary User Interface (`LocalLLMActivity`) for configuration and extended interactions, and a lightweight System UI Overlay (`AssistantSession`) for persistent, system-wide access. Both rely on a shared Singleton inference engine (`LLMManager`) to ensure rapid response times and consistent state.

### High-Level Component Diagram

```mermaid
graph TD
    A[Microphone] -->|Continuous Audio| B("WakeWordService<br/>Vosk Offline STT")
    B -->|Broadcast WAKE_WORD_DETECTED| C(AssistantVoiceInteractionService)
    C -->|Trigger showSession| D("AssistantSession<br/>System UI Overlay")
    A -->|On-Demand Audio| D
    
    A -->|On-Demand Audio| E("LocalLLMActivity<br/>Main App UI")
    
    D <-->|Prompt / Streaming Tokens| F{"LLMManager<br/>Singleton Inference Engine"}
    E <-->|Prompt / Streaming Tokens| F
    
    F -->|Model Initialization| G[("LiteRT / TensorFlow Lite")]
    
    D -->|Parsed <TOOL> Tags| H(VehicleManager)
    E -->|Parsed <TOOL> Tags| H
    
    H -->|CarPropertyManager| I[AAOS VHAL\nHardware Actuation]
    
    D -->|Streaming Chunks| J(TextToSpeech TTS)
    E -->|Streaming Chunks| J
```

### Class & Structural Block Diagram

The following diagram illustrates the class relationships, dependencies, and interfaces between the core components of the application.

```mermaid
classDiagram
    class LocalLLMActivity {
        -SharedPreferences prefs
        -TextToSpeech tts
        -ChatAdapter chatAdapter
        +onCreate()
        +initLlm()
        -processQuery(prompt, isVoice)
        -executeToolCall(toolCall)
    }

    class AssistantSession {
        -TextToSpeech tts
        -SpeechRecognizer speechRecognizer
        +onCreate()
        +onShow(args, showFlags)
        -handleQuery(query)
        -processQuery(query, prefixSpan)
        -executeToolCall(toolCall)
    }

    class LLMManager {
        <<Singleton>>
        -LlmInference engine
        -Conversation conversation
        +isFirstMessage: Boolean
        +isWarmingUp: Boolean
        +initialize(context, modelPath, useCpu)
        +warmUpSystemPrompt(context)
        +resetConversation()
        +getSystemPrompt(context): String
    }

    class WakeWordService {
        <<ForegroundService>>
        -SpeechService speechService
        -Model voskModel
        -wakeWord: String
        +onCreate()
        +recognizerSetup()
        -checkWakeWord(hypothesis)
    }

    class AssistantVoiceInteractionService {
        -BroadcastReceiver receiver
        +onReady()
        +onShutdown()
    }

    class VehicleManager {
        <<Object>>
        -CarPropertyManager carPropertyManager
        +init(context)
        +setHVACTemperature(temp)
        +setDefroster(enabled)
        +setSeatHeater(level)
    }
    
    %% Relationships
    LocalLLMActivity --> LLMManager : Initializes and Queries
    AssistantSession --> LLMManager : Queries
    LocalLLMActivity --> VehicleManager : Injects Tool Actions
    AssistantSession --> VehicleManager : Injects Tool Actions
    LocalLLMActivity --> WakeWordService : Starts and Stops
    WakeWordService ..> AssistantVoiceInteractionService : Broadcasts Intent
    AssistantVoiceInteractionService --> AssistantSession : Triggers showSession
    
    %% Hardware bindings
    VehicleManager ..> CarPropertyManager : AAOS VHAL
    WakeWordService ..> AudioRecord : Vosk Mic Stream
    AssistantSession ..> SpeechRecognizer : Android STT
```

## Main Components & Classes

### 1. `LLMManager.kt` & `GeminiManager.kt` (Inference Engines)
- **Local Model (`LLMManager`)**: Manages the `LiteRT` (`litertlm`) execution for on-device inference (e.g. Gemma, Qwen). Uses a native persistent Key-Value (KV) cache to maintain conversation history across multi-turn interactions. By selectively injecting only the incremental user queries (and avoiding prompt bloat of the system instructions on follow-up turns), it achieves a Time-To-First-Token (TTFT) of **~2.5 seconds** completely offline.
- **Cloud Model (`GeminiManager`)**: Manages cloud-based LLM inference (e.g. Gemini 1.5 Flash). Optimized for real-time responsiveness using **Server-Sent Events (SSE) Streaming API** (`streamGenerateContent?alt=sse`). This custom network layer reads raw TCP socket buffers in real-time to bypass HTTP sync blocks, dropping TTFT latency to **< 1.5 seconds**.
- **Context Auto-Clearing**: Employs heuristic-based recovery blocks. If the LLM generates a suspicious error (e.g., "busy", "invoke") or hits the absolute KV cache ceiling, the engines execute a graceful sliding window reset, purging old turns while preserving the `[Current Vehicle State]`.

### 2. `LocalLLMActivity.kt` (Main Application UI)
A traditional Android Activity serving as the comprehensive dashboard.
*   **Role**: Allows users to load specific `.bin`/`.task` models, toggle the wake word, configure system prompts, and interact via a standard chat interface.
*   **Features**: Manages SharedPreferences to ensure settings (like the Wake Word toggle state) persist across app updates and automotive reboots.

### 3. `WakeWordService.kt` (Continuous Background Listener)
A highly optimized Android Foreground Service (Type: Microphone).
*   **Role**: Runs continuously in the background, analyzing microphone input purely offline.
*   **Library**: Utilizes the **Vosk API** (`com.alphacephei:vosk-android`) to provide highly accurate, offline acoustic model matching without requiring Google Cloud or network access.
*   **Trigger**: When it detects the configured wake word (e.g., "hey auto"), it broadcasts an internal intent `com.example.gemininano.WAKE_WORD_DETECTED`.

### 4. `AssistantVoiceInteractionService.kt` & `AssistantSession.kt` (System UI Overlay)
- `AssistantVoiceInteractionService` extends the Android OS framework to register the application as the default Digital Assistant, overriding Google Assistant.
- `AssistantSession` generates the transparent UI overlay (bottom sheet) over active applications (e.g., Maps).
- **Lifecycle Hardening (`setKeepAwake`)**: Wraps asynchronous LLM inference and long-running hardware actuation callbacks with `VoiceInteractionSession.setKeepAwake(true)` to prevent the Android System Voice Watchdog from prematurely killing the UI overlay during dense AI tasks.
- **Streaming TTS Engine**: Parses AI output dynamically on-the-fly and feeds chunks directly to `TextToSpeech` via `QUEUE_ADD`.

### 5. `ToolManager.kt` (RAG Engine)
- Contains definitions for all 20+ Automotive Voice actions.
- Features a **Semantic Search Engine** to extract relevant tools via sentence embedding similarity. For the Cloud Model, limits context to top-K tools. For the Local Model, bypasses the limit to statically inject all tools, ensuring the KV cache never loses track of available capabilities on follow-up turns.

### 6. `VehicleManager.kt` (VHAL Integration)
- Connects directly to the Android `CarPropertyManager` to translate logical XML tool calls (`<TOOL>setTemperature(22)</TOOL>`) into raw automotive hardware arrays.
- Implements **Hardware Write Pre-Checks**: Evaluates the current state of physical properties (e.g., HVAC temperature) prior to executing writes. If the target value matches the current state, it suppresses the redundant write, mitigating 6-second VHAL watchdog timeouts and ensuring seamless multi-tool orchestration.

## Tool Command Parsing & Navigation Logic
The application employs a regex-based stream interception mechanism to handle tool calls dynamically as the LLM generates tokens, without waiting for the full response to finish.

1.  **System Prompt Instruction**: The LLM is instructed via its system prompt to wrap any hardware commands in XML-like tags, for example: `<TOOL>setTemperature(72)</TOOL>`.
2.  **Stream Interception**: As tokens stream into the `onMessage` callback from LiteRT, the UI appends them to a `lastResponseBuilder`.
3.  **Regex Matching**: On every new token chunk, the app runs the regex `<TOOL>(.*?)</TOOL>` against the accumulated string.
4.  **Execution & UI Stripping**: If a match is found:
    *   The command string (e.g., `setTemperature(72)`) is extracted and passed to `VehicleManager.kt`.
    *   The `VehicleManager` parses the command name and arguments, mapping them directly to `CarPropertyManager` properties (like `VehiclePropertyIds.HVAC_TEMPERATURE_SET`).
    *   The `<TOOL>...</TOOL>` block is immediately replaced with an empty string in the display buffer, ensuring the user never sees the raw code on the screen.
5.  **Deduplication**: A `Set` tracks executed commands to prevent the same tool call from firing multiple times during the streaming process.

## Speech and Audio Engines

To provide a seamless, automotive-grade voice experience, the application splits audio responsibilities across distinct engines based on the specific lifecycle of the interaction.

### 1. Wake Word STT Engine: Vosk API (Offline)
*   **Purpose**: Continuous, passive background listening for the "hey auto" trigger.
*   **Why Vosk?**: Android's built-in `SpeechRecognizer` emits an invasive "beep" sound every time it starts listening, which makes it unusable for silent, continuous background monitoring. Vosk (`com.alphacephei:vosk-android`) uses a lightweight acoustic model (under 50MB) loaded into RAM that runs entirely offline. It processes audio directly from the microphone buffer via `AudioRecord`, avoiding all system-level UI interruptions.

### 2. Active Command STT Engine: Android SpeechRecognizer
*   **Purpose**: High-fidelity transcription of the user's actual command once the assistant overlay is visible.
*   **Why Android API?**: Once the overlay (`AssistantSession`) pops up, the app switches to the system's native `SpeechRecognizer` (`android.speech.SpeechRecognizer`). This engine is highly optimized for complex sentences and intent parsing.
*   **Optimization**: The app explicitly omits the `EXTRA_PARTIAL_RESULTS` flag from the intent. This forces the STT engine to trigger its "end of speech" callback immediately upon trailing silence, cutting out artificial timeout delays and passing the text to the LLM instantly.

### 3. Text-to-Speech (TTS) Engine: Android Native TTS
*   **Purpose**: Speaking the AI's generated response back to the driver.
*   **Streaming Implementation**: The LLM output is streamed, but TTS engines cannot speak half a word. The application uses a custom regex sentence boundary chunker (`"^(.*?)([.!?]+(?:\\s+|$)|\\n)".toRegex()`). 
*   **Behavior**: As tokens arrive, the regex looks for punctuation (`.`, `!`, `?`) or newlines (`\n`). When a complete sentence or list item is formed, it is sliced off and pushed to the TTS `QUEUE_ADD` buffer. This allows the car to start speaking the first sentence while the LLM is still generating the second sentence, significantly reducing perceived latency.

## Sequence Diagram: Wake Word to Hardware Actuation

The following sequence details the ultra-low latency loop from the moment the user speaks the wake word to the moment the hardware reacts.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant V as WakeWordService (Vosk)
    participant AVIS as AssistantVoiceInteractionService
    participant AS as AssistantSession (Overlay)
    participant STT as SpeechRecognizer
    participant LLM as LLMManager (LiteRT)
    participant VM as VehicleManager (VHAL)
    participant TTS as TextToSpeech

    User->>V: Speaks "Hey auto"
    V->>AVIS: Broadcasts WAKE_WORD_DETECTED
    V->>V: Pauses listening (prevents loops)
    AVIS->>AS: Calls showSession()
    AS-->>User: Pops up UI Overlay
    AS->>STT: Starts listening automatically
    
    User->>STT: Speaks "Set temperature to 72"
    STT-->>AS: Returns text "Set temperature to 72"
    
    AS->>LLM: sendMessageAsync("Set temperature to 72")
    Note over LLM: KV Cache is pre-warmed.<br/>Prefill takes under 50ms.
    
    LLM-->>AS: Streams tokens: "<TOOL>"
    LLM-->>AS: Streams tokens: "setTemperature(72)"
    LLM-->>AS: Streams tokens: "</TOOL> Sure!"
    
    AS->>AS: Regex captures <TOOL> tags
    AS->>VM: executeToolCall("setTemperature(72)")
    VM->>VM: Actuates HVAC hardware
    
    LLM-->>AS: Streams tokens: " The cabin is "
    LLM-->>AS: Streams tokens: "now heating up.\n"
    
    AS->>AS: Regex chunks sentence by '\n' or '.'
    AS->>TTS: Sends chunk to speak ("Sure! The cabin is now heating up.")
    TTS-->>User: Plays audio response
```

## Third-Party Libraries

*   **LiteRT (TensorFlow Lite)**: Google's edge inference engine. Enables running multi-billion parameter quantized models (Gemma, Qwen) directly on the automotive Snapdragon SOC without internet access.
*   **Vosk API**: Provides offline, silent speech recognition for continuous background wake-word listening.

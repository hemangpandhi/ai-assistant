# VehicleEdgeAssistant Class Reference

This document provides a comprehensive dictionary of every core Kotlin class in the `VehicleEdgeAssistant` project, organized by logical domain. It details exactly what each class is responsible for.

---

## 1. Core Orchestration & AI Management

*   **`LLMManager`**
    *   **Responsibility:** The brain of the application. It acts as a wrapper over the native C++ `liblitertlm_jni.so` Edge LLM engine (LiteRT). It manages loading models (Gemma, Qwen), maintaining the persistent Key-Value (KV) cache, assembling the final `System Prompt` (merging vehicle context + XML tool tags), and streaming token generation back to the UI.
*   **`MemoryManager`**
    *   **Responsibility:** Maintains the short-term sliding context window for the conversation. It stores user queries, assistant responses, and tool feedbacks, ensuring that the conversational history does not exceed the LLM's token limit.
*   **`SemanticSearchManager`**
    *   **Responsibility:** The mathematical embedding engine for the Slow-Path RAG. If keyword matching fails, this class uses on-device vector embeddings (like `TfLite` embedding models) and Cosine Similarity to mathematically determine which tool the user is trying to invoke based on the meaning of their sentence.
*   **`AnthropicManager` / `GeminiManager`**
    *   **Responsibility:** Fallback cloud modules. If the local Edge NPU is unavailable or the user forces a cloud model in the UI, these classes handle ultra-fast Server-Sent Events (SSE) socket connections to Google Gemini or Anthropic Claude APIs.

---

## 2. Tool & RAG System (The "Hands" of the AI)

*   **`ToolManager`**
    *   **Responsibility:** The central execution hub. On startup, it parses `vehicle_skills_registry_v2.0.json`. During runtime, it exposes `getLlmToolsPrompt()` to filter relevant tools using Dual-Path RAG (Fast-Path keywords vs Slow-Path semantic). It intercepts `<TOOL>` regex tags from the LLM output and routes them to the correct `ToolHandler`.
*   **`ToolHandlerRegistry`**
    *   **Responsibility:** A factory class that registers and maintains references to all specific handler implementations, passing intercepted commands down to the correct module.
*   **`ToolHandler` (Interface)**
    *   **Responsibility:** The base interface that all specific hardware/software skill handlers must implement. Contains the `execute(context, toolCall, args): ToolExecutionResult` method.
*   **`ToolExecutionResult`**
    *   **Responsibility:** A data class used to pass feedback back to the Agentic Loop. It contains a `boolean` success flag and a `String` feedback message (which the LLM uses as a "System Observation").
*   **`ParameterParser`**
    *   **Responsibility:** A utility class that cleanly extracts regex arguments from the raw `<TOOL>command(arg1, arg2)</TOOL>` string into structured lists.

### 2.1 Specific Tool Handlers

*   **`HVACToolHandler`**
    *   **Responsibility:** Controls Climate systems. Reads and sets temperatures, fan speeds, seat heaters, and defrosters by sending precise integers and floats to `CarPropertyManager`.
*   **`MediaToolHandler`**
    *   **Responsibility:** Controls Audio. Simulates hardware media key events (`KEYCODE_MEDIA_PLAY`, `NEXT`, `PREVIOUS`) and uses Android Intents to launch specific artists or songs in Spotify/YouTube Music.
*   **`NavigationToolHandler`**
    *   **Responsibility:** Controls Maps. Issues `geo:` intents to launch turn-by-turn navigation, and uses the `Overpass API` to query hyper-local Points of Interest (POIs) based on GPS coordinates.
*   **`SystemToolHandler`**
    *   **Responsibility:** Handles generic OS requests. Examples include saving memory to SharedPreferences, querying mock weather data, getting news highlights, and opening third-party apps.
*   **`EVHandler`**
    *   **Responsibility:** Specific logic for Electric Vehicles (EV). It calculates optimized charging curves, estimates range degradation based on temperature, and analyzes battery health.
*   **`SafetyAndCareHandler`**
    *   **Responsibility:** Manages driver safety routines. Evaluates fatigue metrics (from mock VHAL sensors), handles emergency diagnostics, and explains child-seat installation.
*   **`WindowToolHandler`**
    *   **Responsibility:** Hardware logic for opening/closing windows and the sunroof.
*   **`MacroOrchestrationHandler`**
    *   **Responsibility:** Responsible for executing complex, multi-step macros (e.g., "Prepare for road trip" which involves modifying navigation, HVAC, and music simultaneously).
*   **`CommunicationToolHandler`**
    *   **Responsibility:** Handles Telecom intents like `ACTION_DIAL` and SMS messaging routines.

---

## 3. Vehicle Hardware Abstraction (VHAL)

*   **`VehicleManager`**
    *   **Responsibility:** The bridge to the car's physical hardware. It binds to the Android `CarPropertyManager`. On boot, it maps JSON strings to integer Property IDs. It continuously reads CAN bus telemetry (gear, speed, EV battery) and provides `getSensorContext()` to the LLM. It also exposes `writeProperty()` to safely actuate physical hardware.
*   **`LocationManager`**
    *   **Responsibility:** A simple wrapper that interfaces with Android's `FusedLocationProviderClient` or the VHAL's GPS sensor to provide the `NavigationToolHandler` with the car's current latitude and longitude.

---

## 4. UI & Android OS Services

*   **`AssistantSession`**
    *   **Responsibility:** The core Voice UI overlay. Inherits from `VoiceInteractionSession` to act as the OS-level system assistant. It manages the `SpeechRecognizer` (listening to the user), formats the text using a Typewriter animation, short-circuits hidden `<TOOL>` tags, and pipelines the final response to the `TextToSpeech` (TTS) engine.
*   **`AssistantSessionService` & `AssistantVoiceInteractionService`**
    *   **Responsibility:** The Android boilerplate services required by the OS to register the app as the default system-level Voice Assistant (allowing steering wheel button triggers).
*   **`LocalLLMActivity`**
    *   **Responsibility:** The standard Android configuration application (the app icon you tap on the launcher). It allows the user to download models, switch UI layouts, and test text-based prompts directly.
*   **`WakeWordService`**
    *   **Responsibility:** A Foreground Service running an offline Vosk Acoustic Model. It continuously listens to the microphone buffer for the phrase "Hey Auto" without draining the battery or emitting audio beeps. When detected, it launches the `AssistantSession`.
*   **`VoiceAnimationView`**
    *   **Responsibility:** A custom Android View that draws the dynamic, pulsating audio waveform based on the `SpeechRecognizer` RMS decibel levels to provide visual feedback that the car is listening.
*   **`ChatAdapter` & `ChatMessage`**
    *   **Responsibility:** Standard RecyclerView UI components used to display the text-based chat history inside `LocalLLMActivity`.

---

## 5. Telemetry & Testing

*   **`LatencyLogger`**
    *   **Responsibility:** An incredibly precise timestamping utility. It tracks Time-To-First-Token (TTFT), VHAL write latency, and RAG execution times, printing them to Logcat for performance optimization.
*   **`AutomatedTestSuite`**
    *   **Responsibility:** A headless testing module that sequentially runs 15+ complex natural language prompts through the `ToolManager` to verify that tool routing and agentic loops do not break during development.

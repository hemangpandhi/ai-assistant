# Local AI Assistant: Architecture Details

This document outlines the internal architecture of the Android Automotive Local AI Assistant. The system is designed to provide ultra-low latency, 100% offline conversational capabilities with direct read/write access to vehicle hardware.

## System Block Diagram

The following diagram illustrates the data flow from user input through the inference engine, and out to the vehicle's hardware actuators and speech interfaces.

```mermaid
graph TD
    %% Styling
    classDef default fill:#1E1E1E,stroke:#333,stroke-width:2px,color:#FFF;
    classDef ui fill:#2A3F54,stroke:#4CAF50,stroke-width:2px,color:#FFF;
    classDef logic fill:#4B2E83,stroke:#9C27B0,stroke-width:2px,color:#FFF;
    classDef hardware fill:#D84315,stroke:#FF9800,stroke-width:2px,color:#FFF;
    classDef model fill:#006064,stroke:#00BCD4,stroke-width:2px,color:#FFF;

    %% User Inputs
    UserVoice(🗣️ User Voice) --> |Microphone| STT[🎤 VoskManager<br>Offline Speech-to-Text]:::ui
    UserText(⌨️ User Text) --> UI

    %% Core UI
    STT --> |Text Query| UI[📱 LocalLLMActivity / AssistantSession<br>Android UI & Session State]:::ui
    
    %% Session Manager
    UI --> |Process Query| SM[🧠 LLMManager<br>Orchestration & Context]:::logic
    
    %% Context Providers
    SM --> |Get Sensor Data| VM[🚗 VehicleManager<br>Android VHAL / CarPropertyManager]:::hardware
    VM -.-> |Live Telemetry| SM
    SM --> |Get Memory| Prefs[(💾 SharedPreferences<br>User Preferences)]:::hardware

    %% Inference Engine
    SM --> |System Prompt + Query| Engine{⚙️ LiteRT-LM Engine / Cloud API<br>Inference Backend}:::model
    
    %% Models
    Engine -.-> |Local Inference| LocalModel[📱 Local Models<br>Gemma, Qwen, SmolLM]:::model
    Engine -.-> |Network API| CloudModel[☁️ Cloud Models<br>Gemini, Claude]:::model

    %% Output Loop
    Engine --> |Generated Response| SM
    
    %% Execution & TTS
    SM --> |Detect &lt;TOOL&gt; Tags| TM[🛠️ ToolManager<br>Tool Execution Engine]:::logic
    SM --> |Clean Text| TTS[🔊 Android TTS<br>Text-to-Speech]:::ui

    %% Tool Actions
    TM --> |HVAC/Hardware Control| VM
    TM --> |Launch Apps| Intents[📱 Android Intents<br>Maps, Dialer, Music]:::hardware
```

## Component Breakdown

### 1. Input Layer
- **VoskManager**: Handles 100% offline Speech-to-Text (STT) conversion. It listens to microphone input when triggered and converts spoken words into a text query, ensuring privacy and zero latency.
- **LocalLLMActivity / AssistantSession**: The primary user interface. It manages the conversational state, displays the animated pulsing UI, and handles user interactions (text or voice).

### 2. Orchestration Layer
- **LLMManager**: The brain of the application. It takes the user's query, fetches live vehicle telemetry from the `VehicleManager`, retrieves user preferences from `SharedPreferences`, and dynamically constructs a massive **System Prompt**. This prompt instructs the AI on current conditions, available tools, and strict formatting rules.

### 3. Hardware / Telemetry Layer
- **VehicleManager**: A critical component that directly binds to the Android Automotive `CarPropertyManager`.
  - **Read**: Subscribes to VHAL (Vehicle Hardware Abstraction Layer) properties like HVAC temperature, door status, gear selection, and fuel level.
  - **Write**: Exposes setter methods to physically change vehicle state (e.g., turning on seat heaters, adjusting AC).

### 4. Inference Engine
- **LiteRT-LM / Cloud API**: The execution environment. 
  - For local execution, it uses the LiteRT-LM (formerly TensorFlow Lite) C++ backend via JNI to run highly optimized `.litertlm` models directly on the device's CPU or GPU. 
  - For cloud execution, it routes the query to Google Gemini or Anthropic Claude APIs using REST.

### 5. Execution Layer
- **ToolManager**: Parses the raw output from the LLM. If it detects the `<TOOL>` syntax (e.g., `<TOOL>setSeatHeater(2)</TOOL>`), it intercepts the command and executes the corresponding native Android function rather than showing the raw tag to the user.
- **Android Intents**: Used by the `ToolManager` to launch external applications like Google Maps for navigation or the Dialer for phone calls.
- **Android TTS**: Cleans the LLM's response (removing tool tags) and reads the conversational reply aloud to the driver.

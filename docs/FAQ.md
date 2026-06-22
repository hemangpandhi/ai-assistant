# VehicleEdgeAssistant - Frequently Asked Questions (FAQ)

This document anticipates and answers common questions about the architecture, capabilities, and deployment of the VehicleEdgeAssistant project.

## 1. General & High-Level Questions

**Q: What exactly is VehicleEdgeAssistant?**
A: It is a local, AI-powered Voice Assistant designed specifically for Android Automotive OS (AAOS). Instead of relying on a cloud server like Alexa or Google Assistant, it runs a Large Language Model (LLM) like Gemma directly on the car's hardware (GPU/NPU) to control physical vehicle hardware (HVAC, Windows) completely offline.

**Q: Does it require an internet connection?**
A: **No.** The core execution is 100% offline. The Wake Word engine (Vosk), the Speech-to-Text transcriber, the Edge LLM (LiteRT), the Tool Routing, and the Text-to-Speech (TTS) engine all run natively on the vehicle's System-on-Chip (SoC). Internet is only required if you explicitly use a cloud model fallback (like Gemini Pro) or query real-time web data (like Overpass API for map points).

**Q: What LLM models are supported?**
A: Out of the box, it supports any `.litertlm` (LiteRT/TFLite) quantized model. We have heavily tested it with **Gemma 4 E2B**, **Qwen 2.5 1.5B**, and **SmolLM 135M**.

---

## 2. Architecture & Performance

**Q: How does the AI actually control the physical car?**
A: Through the **Vehicle Hardware Abstraction Layer (VHAL)**. The app binds to the Android `CarPropertyManager`. When the LLM decides to turn on the AC, it generates an XML tag like `<TOOL>setHVACTemperature(25, 1)</TOOL>`. The `ToolManager` intercepts this, parses it, and uses `VehicleManager` to write the corresponding integer payload to the AOSP VHAL bus, which physically actuates the AC unit.

**Q: LLMs usually take 5 seconds to respond. How is this fast enough for a car?**
A: We implemented several extreme latency optimizations:
1. **KV Caching:** We only inject the massive "System Prompt" (with all the rules and tools) on the very first turn. For follow-up questions, the C++ engine retrieves the cache directly from native RAM, making follow-ups almost instantaneous.
2. **Sentence-Boundary Streaming:** The Text-to-Speech (TTS) engine doesn't wait for the LLM to finish thinking. As soon as the LLM generates a punctuation mark (`.` or `?`), the app instantly speaks that specific sentence while the LLM continues generating the rest of the paragraph in the background.

**Q: Why does the typing effect on the screen perfectly match the spoken voice?**
A: We intentionally synchronized the visual `typewriterJob` speed to **35ms** per character (roughly 150 Words Per Minute), which mathematically mirrors the default Google Text-To-Speech engine's speaking rate.

---

## 3. Tooling & RAG (Retrieval-Augmented Generation)

**Q: If the car has 500 features, won't injecting all those tools into the prompt overload the LLM?**
A: Yes, which is why we built a **Dual-Path RAG Engine**. 
When you speak, the `ToolManager` filters the tools down to only the 1-3 most relevant ones before waking up the LLM. 
*   **Fast-Path:** It checks your sentence against hardcoded `keywords` in the JSON registry (0ms latency).
*   **Slow-Path:** If you use weird slang, it uses a local mathematical embedding model (Semantic Search) to find the conceptually closest tool (e.g., "I'm freezing" mathematically maps to `setHVACTemperature`).

**Q: How do I add a new feature (like controlling the Sunroof)?**
A: **Zero-Code.** You do not need to write Kotlin. Open `assets/vehicle_skills_registry_v2.0.json`, add a new JSON block with the tool name (`openSunroof`), the VHAL `property_id` (e.g., `320865540`), and some keywords. The system will dynamically parse it on the next boot and the LLM will instantly know how to use it.

---

## 4. Agentic Loops & Multi-Turn Conversations

**Q: What is the "Agentic Loop"?**
A: Traditional assistants are "Request -> Response". The VehicleEdgeAssistant is autonomous. If you say "Find a restaurant," the LLM triggers a tool to search the map. Instead of printing raw map coordinates to the screen, the system **secretly feeds the map results back to the LLM** as a "System Observation". The LLM reads the results, reasons about them, and *then* formulates a natural human response to speak out loud.

**Q: When the AI asks me a follow-up question, the microphone cuts me off before I can answer! Why?**
A: Standard Android `SpeechRecognizer` intents have a default "Silence Timeout" of 500ms. If you paused to think for half a second, it crashed. We have explicitly overridden these parameters in `AssistantSession.kt`, bumping the `COMPLETE_SILENCE_LENGTH_MILLIS` to **3000ms (3 seconds)**. You now have a comfortable window to think and reply.

**Q: Does the AI remember what we talked about 5 minutes ago?**
A: Yes, using the `MemoryManager`. It maintains a sliding window of the last few conversational turns. If the memory buffer gets too large and threatens to crash the LLM context window (KV overflow), the system performs an automated "Graceful Sliding Window Reset" to keep the car stable.

---

## 5. Security & Deployment

**Q: Is my voice data secure?**
A: **100% secure.** Because the Wake Word, Speech-to-Text, and LLM inference all happen on the local silicon, your audio data and conversation history never leave the vehicle. 

**Q: What happens if the AI hallucinates and tries to turn off the engine while driving?**
A: The `vehicle_skills_registry_v2.0.json` acts as a hardcoded whitelist. The LLM cannot invent VHAL property IDs. It can only execute exactly what is defined in the JSON. Furthermore, production deployments rely on Android's native SELinux and `CarPropertyManager` permissions, which physically restrict apps from writing to critical powertrain sensors while in `DRIVE` gear.

## 6. Hardware & Build Requirements

**Q: Can this run on any Android tablet or phone?**
A: Technically yes, the core logic will run on any Android 11+ device. However, to execute the edge LLMs at interactive speeds, the hardware must have a capable GPU (e.g., Adreno 700+ series) or a dedicated NPU (like the Google Tensor G3/G4 or Qualcomm Hexagon DSP). 

**Q: Why does the app request `SYSTEM_ALERT_WINDOW` and the Assistant Role?**
A: To provide a true automotive experience, the app needs to draw its transparent UI overlay on top of any active application (like Google Maps) without forcing the user to leave their current screen. The Assistant Role is required so that the physical steering wheel voice button routes directly to our app instead of the default Google Assistant.

**Q: I get an `INSTALL_FAILED_INSUFFICIENT_STORAGE` error when trying to deploy the APK. Why?**
A: This is a known issue on some Android development devices when repeatedly overwriting large APKs that contain embedded `.litertlm` model weights. Always run `adb uninstall com.example.gemininano` before deploying a fresh build to clear the corrupted storage state.

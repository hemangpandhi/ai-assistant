# Production Readiness & Improvements Guide (AAOS AI Assistant)

Transitioning this prototype Edge AI Assistant into a production-grade Android Automotive OS (AAOS) vehicle requires addressing several architectural, security, and performance bottlenecks. Below is a comprehensive list of improvements to implement before SOP (Start of Production).

## 1. Performance & Hardware Optimization

*   **DSP-Accelerated Wake Word Detection:** Currently, the `WakeWordService` likely keeps the CPU awake and continuously processes audio through the standard Android `AudioRecord` API. **Production Fix:** Offload wake word detection (e.g., "Hey Assistant") directly to the Qualcomm Always-On Audio (AOA) DSP using the Android `SoundTrigger` API. This reduces standby power consumption from watts to milliwatts.
*   **AOT Shader Compilation for GPU/NPU:** First-time initialization of the LLM takes 10-20 seconds due to Vulkan/OpenCL shader compilation. **Production Fix:** Pre-compile the pipeline cache during the factory firmware build or during the OTA installation phase, ensuring near-instant cold starts.
*   **KV Cache Management:** The app currently relies on a "Sliding Window Reset Hack" when the context window fills up (preventing OOM errors). **Production Fix:** Implement an asynchronous background summarization loop. When the context reaches 80%, use a smaller model (or idle cycles) to summarize the oldest 50% of the conversation into a dense memory block, ensuring infinite conversational memory without crashing.

## 2. Security, Privacy & Permissions

*   **Platform Signing & System Privileges:** The app currently uses standard Android runtime permissions. **Production Fix:** The app must be signed with the OEM `platform` key and integrated via an `Android.bp` blueprint as a privileged system app (`priv-app`). This grants it automatic, silent access to restricted `android.car.permission.CONTROL_CAR_*` permissions without prompting the user.
*   **Sandboxed Tool Execution:** Tools are executed sequentially in the same process. **Production Fix:** Isolate destructive VHAL (Vehicle Hardware Abstraction Layer) calls. Implement a strict intent-filter verification to ensure that only the trusted LLM process can broadcast execution intents, preventing malicious third-party apps from spoofing `<TOOL>openTrunk()</TOOL>`.
*   **Local-Only Privacy Enclaves:** Ensure that microphone data and user conversation logs are strictly kept in memory or encrypted local storage (`EncryptedSharedPreferences`). Disable any accidental cloud fallbacks (like Analytics or Crashlytics) for raw prompt data to comply with GDPR/CCPA.

## 3. VHAL (Vehicle HAL) Robustness

*   **Dynamic Area ID Resolution:** The `VehicleManager` currently relies on hardcoded Area IDs (e.g., Area `0` or Driver Seat `1`) which can crash on different vehicle configurations. **Production Fix:** On boot, the Assistant should query `CarPropertyManager.getPropertyConfig()` for all supported zones and map them to a dynamic internal registry, ensuring the LLM can seamlessly handle requests like "turn on the back-left seat heater" across different car models.
*   **Hardware State Synchronization:** If the user manually turns the AC dial while the AI is "thinking" about a climate command, a race condition occurs. **Production Fix:** Subscribe the LLM's dynamic context engine directly to the `CarPropertyEventCallback` stream, ensuring the AI's internal representation of the car's state is always accurate to the millisecond before it executes a tool.

## 4. LLM & Prompt Engineering

*   **Constrained Decoding (Grammar Parsing):** Currently, we rely on Regex `(?i)<TOOL>.*?(</TOOL>|$)` to catch hallucinations. **Production Fix:** Use LiteRT's constrained decoding (Logit biasing or Grammar/JSON mode). This forces the LLM at the C++ inference level to *only* output valid `<TOOL>` syntax when calling a tool, completely eliminating syntax hallucinations.
*   **Semantic Router Refinement:** The `SemanticSearchManager` uses cosine similarity to inject the top 8 tools. **Production Fix:** Train a tiny, highly quantized classifier (e.g., a 10MB BERT model) that routes the query to a specific domain (Climate, Media, Navigation) *before* hitting the LLM. This drastically reduces the context window size and improves Time-To-First-Token (TTFT).

## 5. Build & Deployment

*   **Remove `extractNativeLibs="true"`:** The `AndroidManifest.xml` currently forces native library extraction. **Production Fix:** Set this to `false` and ensure your `app/build.gradle.kts` packages `.so` files uncompressed (`packagingOptions { jniLibs { useLegacyPackaging = false } }`). This allows the Android OS to memory-map the Qualcomm NPU binaries directly from the APK, saving hundreds of megabytes of flash storage.
*   **ProGuard / R8 Obfuscation:** Enable `minifyEnabled true` and `shrinkResources true` to strip out unused LiteRT debugging symbols and reduce the memory footprint of the Dalvik executable.

## 6. Tool Registry Architecture (vehicle_skills_registry.json)

To support a complete, production-ready Software Defined Vehicle (SDV), the `vehicle_skills_registry.json` schema must evolve beyond simple prompt injection to natively handle the complexities of automotive driving logic:

*   **Safety & Driving State Constraints:** Implement `"safety_constraints": { "requires_parked": true, "max_speed_kmh": 0 }` to prevent the AI from executing hazardous commands (like opening the trunk or playing a video) while the vehicle is in motion, verifying against `PERF_VEHICLE_SPEED` before execution.
*   **Multi-Zone Audio & Role-Based Access (RBAC):** Replace hardcoded `area_id: 0` with `"area_mapping_strategy": "DYNAMIC_BY_AUDIO_ZONE"`. The Assistant must read from the `SoundTrigger` API to determine which microphone triggered the wake word so that "turn on my seat heater" works independently for the driver and passenger. Furthermore, implement `"role_restrictions": ["driver"]` to prevent children in the back seat from unlocking doors or turning off the ignition.
*   **Slot Filling & Parameter Validation:** Instead of hoping the LLM outputs a complete tool string, adopt an OpenAPI-style JSON schema for tools (`"parameters": { "required": ["query"] }`). If a user gives a vague command ("Find me a place"), the `ToolManager` will natively detect the missing parameter and automatically prompt the user ("What would you like to search for?") without crashing.
*   **State Toggling & Relative Adjustments:** Support commands like "turn the fan down" by adding `"read_property_id": 356517131`. This explicitly links the tool to a readable VHAL property so the orchestrator can securely fetch the current state, compute the delta, and write the new value automatically.
*   **Latency Masking Profiles:** Flag network-dependent or computationally heavy tools with `"execution_profile": "ASYNC_LONG"`. This allows the UI to instantly deploy a placeholder audio response ("Let me look that up for you...") to mask the API latency, improving the perceived fluidity of the conversation.
*   **Offline Fallback Routing:** Add `"offline_capable": false` flags to internet-dependent tools. If the vehicle loses 5G connectivity (e.g., entering a tunnel), the semantic search engine will instantly omit these tools from the LLM context to prevent the NPU from wasting battery on impossible tasks.

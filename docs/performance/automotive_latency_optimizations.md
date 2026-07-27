# Automotive Assistant Latency & Stability Optimizations

This document summarizes the key architectural changes, stability fixes, and UX latency optimizations applied to the `VehicleEdgeAssistant` to make it production-ready for Automotive Android (AAOS).

## 1. Process Stability & Lifecycle Management

### Resolving Native Vosk JNI Crashes
Previously, the background `WakeWordService` was highly unstable, crashing the entire `com.example.gemininano` process whenever the listener restarted. 
- **The Issue:** A race condition occurred where the Main UI thread would execute `recognizer.close()` while the background I/O loop was still actively attempting to feed raw audio bytes to the native C++ Kaldi instance. This triggered a `SIGSEGV` segmentation fault.
- **The Fix:** Implemented a thread synchronization gate (`listeningJob?.join()`) to ensure the I/O thread gracefully terminates *before* the native JNI instance is deallocated from memory.

### VHAL Property Hardening
- Added the missing `android.car.permission.CAR_EXTERIOR_ENVIRONMENT` permission to the `AndroidManifest.xml`.
- Wrapped Vehicle Property subscriptions in `VehicleManager.kt` inside `try-catch` blocks. This ensures that even if an OEM hasn't implemented a specific sensor (like window states or exterior temp), the assistant will gracefully bypass it instead of crashing the initialization sequence.

---

## 2. TTFT (Time To First Token) Optimization

### Background KV Cache Pre-Warming
The AI Assistant's massive System Prompt—which includes all the Vehicle API descriptions, XML syntax rules, and sensor states—takes significant compute time to process. Initially, this caused a **~23-second TTFT penalty** on the very first query.
- **The Fix:** Implemented a new `prewarm()` coroutine in `LLMManager.kt`. 
- **How It Works:** As soon as the `WakeWordService` boots in the background, it silently loads the 2GB model into the NPU and sends a dummy initialization prompt. The LiteRT engine processes the massive context window and permanently caches the Key-Value (KV) vectors. 
- **The Result:** When the driver presses the voice button, the initial 23-second penalty is entirely bypassed, dropping the first-query TTFT down to **~1.8 - 5.0 seconds**.

---

## 3. Perceived Latency (UX Optimization)

### System Prompt Tool Reordering
Even with the TTFT reduced to ~3 seconds, there was a perceived delay of an additional **4-6 seconds** of total silence while the assistant generated the execution string for a tool (e.g., `<TOOL>playMusic(relaxing music)</TOOL>`).
- **The Issue:** The System Prompt strictly enforced that the AI must output the `<TOOL>` tag as the *very first* tokens in its response. Because `<TOOL>` strings are stripped from the UI and TTS buffer, the streaming engine was starved of human-readable text until the entire tool was fully parsed.
- **The Fix:** Prompt Engineering. The System Prompt was restructured to explicitly instruct the LLM to output a conversational preamble *before* any tool execution tag (e.g., `"Sure, playing relaxing music for you now. <TOOL>playMusic(...)</TOOL>"`).
- **The Result:** This simple reordering enables the Assistant's Text-to-Speech (TTS) stream to begin playing audio to the driver immediately at the ~1.8s TTFT mark, perfectly masking the background token generation time and achieving **Zero Perceived Latency**.

---

## 4. Compose UI Time-to-First-Frame (TTFF) — target < 100ms

The voice overlay is Compose-hosted inside `AssistantSession` (`VirtualAssistantOverlay` → `ImmersiveAssistantOverlay`). First-frame cost was dominated by Offscreen backdrop passes, border glow, hybrid-face idle loops / `BlurMaskFilter`, and binding `VehicleAgentService` during inflate.

### Two-phase first paint
1. **Lite frame (immediate):** solid scrim + cheap vertical gradient + face with idle motion / blur blooms disabled (`LocalAssistantIdleMotion = false`). Dock / session path (`awaitHotword = false`) initializes backdrop and face alphas to visible so composition 0 is already on-stage.
2. **Rich frame (next vsync):** Offscreen backdrop masks, border glow, debug strip, infinite breath/bob/pulse, and blur blooms enable after `withFrameNanos`.

### Critical-path deferrals
- `startForegroundService` / `bindService` for `VehicleAgentService` runs on first `OnPreDraw`, not inside `onCreateContentView` before return.
- Wake chime / haptic (`AssistantWakeFeedback.play`) waits one frame so `AudioTrack` init cannot block TTFF.
- `VehicleManager.initialize` on VIS `onReady` moved off the main thread.

### Compose prewarm
`AssistantComposePrewarmer` class-loads Compose runtime + theme + `ImmersiveEyesFace` when `VoiceInteractionService.onReady` fires, so the first wake-word session does not pay cold class-load cost.

### Instrumentation
Logcat tag `AssistantUiLatency` marks `contentView start` → `setContentView done` → `first pre-draw` → `first compose frame` → `rich effects enabled`. Aim for first pre-draw / first compose frame under **100ms**.

# Assistant UI/UX — VehicleEdge Integration Guide

This document captures the UI/UX architecture decisions and operational instructions for the Compose design-assistant integration in VehicleEdge (`com.tcs.vehicleassistant`).

Related:

- [assistant-adb.md](./assistant-adb.md) — command cheat sheet
- [assistant-standalone.md](./assistant-standalone.md) — module boundary notes

---

## Goals

1. Move to **Compose** for the assistant view while keeping current **XML voice plates** as a temporary fallback.
2. Keep the **view layer fully decoupled** from business / agent / LLM logic.
3. Make assistant UI **easily swappable per vehicle** via ADB (no rebuild).
4. Ship as **one app APK**; use library modules only where needed for decoupling.

---

## Defaults

| Setting | Default | Notes |
|---------|---------|--------|
| UI renderer | `compose` | Immersive Compose stage in the voice session |
| Face | `hybrid` | Immersive hybrid eyes |
| Backend (UI events) | `DemoAssistantBackend` | Scripted demo for UI validation; real agent bridge is ready to swap |

Legacy XML plates (`polestar`, `pill`, `hud`, …) remain available via ADB and will be discarded gradually.

---

## Architecture

```text
Wake word / Home button (VoiceInteractionService)
        │
        ▼
  AssistantSession
        │
        ├── ui=compose (default) ──► ComposeView → ImmersiveAssistantOverlay
        │                                      │
        │                                      ▼
        │                         AssistantRuntime
        │                         ├─ VehicleAssistantHost (cabin)
        │                         └─ AssistantBackend (Demo now / VehicleAgent later)
        │
        └── ui=xml:* ─────────────► Legacy XML voice-plate layouts
                                    + AssistantViewModel (existing path)
```

### Decoupling rules

- Compose UI (`:assistant`) must **not** import `AgentOrchestrator`, `LLMManager`, `VehicleManager`, or ViewModels.
- Cabin data crosses the boundary only as `AssistantCabinContext` (strings/numbers).
- UI observes `AssistantBackend.events` and forwards mic via `onSpeechInput`.
- Host app owns wiring in `AssistantRuntimeBootstrap` / `VehicleAssistantHost`.

### Modules (one APK)

| Module | Role |
|--------|------|
| `:assistant` | Contracts (`AssistantBackend`, `AssistantHost`, events/models) + Compose faces, immersive overlay, gallery, demo backend |
| `:app` | Host install, ADB receivers, VIS session dual renderer, agent services |

---

## Build and deploy

Requires JDK **17–21** for Gradle (JDK 25 is not supported by this toolchain).

```powershell
# Build + install on a connected device/emulator
.\buildDeploy.ps1

# Optional
.\buildDeploy.ps1 -Clean -Launch
.\buildDeploy.ps1 -Serial emulator-5554
```

Grant mic (and overlay if using the secondary overlay service):

```bash
adb shell pm grant com.tcs.vehicleassistant android.permission.RECORD_AUDIO
adb shell appops set com.tcs.vehicleassistant SYSTEM_ALERT_WINDOW allow
```

---

## ADB — UI renderer (Compose vs XML)

Persisted in SharedPreferences + best-effort `Settings.Global` (`vehicle_assistant_ui`).

```bash
# Compose immersive (default)
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
  -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver \
  --es ui compose

# Legacy XML voice plate
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
  -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver \
  --es ui xml:polestar

# Read current
adb shell am broadcast -a com.tcs.vehicleassistant.action.GET_ASSISTANT_UI \
  -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver
adb logcat -d -s AssistantUi:I | tail -n 3

# Survives process death
adb shell settings put global vehicle_assistant_ui compose
adb shell settings get global vehicle_assistant_ui
```

### UI tokens

| Token | Renderer |
|-------|----------|
| `compose` / `immersive` / `design` / `default` | Compose immersive |
| `xml` / `legacy` | XML Polestar (index 0) |
| `xml:polestar` | XML wide Polestar |
| `xml:pill` | Center pill |
| `xml:side` | Side panel |
| `xml:top` | Top banner |
| `xml:immersive` | Full-screen immersive XML |
| `xml:hud` | HUD |
| `xml:beveled` | Beveled glass |
| `xml:cinematic` | Cinematic letterbox |

XML selection also mirrors into `app_prefs` / `ui_layout_pref` for the legacy path.

---

## ADB — Face (Compose persona)

Persisted in SharedPreferences + `Settings.Global` (`design_assistant_face`). Live while the process is up.

```bash
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceReceiver \
  --es face hybrid

adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_FACE \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceReceiver
adb logcat -d -s AssistantFace:I | tail -n 3

adb shell settings put global design_assistant_face hybrid
adb shell settings get global design_assistant_face
```

### Face tokens

| Token | Face |
|-------|------|
| `none` | Transcript only |
| `eyes` | Immersive eyes |
| `glow` | Immersive glow |
| `hybrid` | Immersive hybrid (**default**) |
| `eporo` | EPORO robot head |
| `fusion` | Fusion (EPORO shell + immersive expressions) |
| `fusionglow` | Fusion + glow capsule eyes |
| `fusioneyes` | Fusion + pale capsule eyes |
| `droid` | Droid |
| `glyph` | Classic glyph |

Aliases include: `off`→`none`, `immersive`→`eyes`, `aura`/`ring`→`glow`, `express`→`fusion`, `classic`→`glyph`.

---

## ADB — Debug strip (Compose immersive)

On debuggable builds the overlay shows model/backend tags + a live log strip. Toggle without reinstalling:

```bash
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver \
  --es visible off

adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_DEBUG_STRIP \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver
adb logcat -d -s AssistantDebugStrip:I | tail -n 3

adb shell settings put global vehicle_assistant_debug_strip off
adb shell settings get global vehicle_assistant_debug_strip
```

Tokens: `on` | `off` | `1` | `0` | `show` | `hide` (default **on**; release APKs never show the strip).

---

## ADB — Idle timeout (auto-close)

Quiet-listening auto-close for the Compose overlay. Default: **5** seconds. `0` disables.

```bash
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_IDLE_TIMEOUT \
  -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver \
  --ei sec 5

adb shell am broadcast -a com.tcs.vehicleassistant.action.GET_IDLE_TIMEOUT \
  -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver
adb logcat -d -s AssistantIdle:I | tail -n 3

adb shell settings put global vehicle_assistant_idle_timeout_sec 5
adb shell settings get global vehicle_assistant_idle_timeout_sec
```

Range: `0`–`600` seconds.

---

## ADB — Launch surfaces

```bash
# Standalone Compose activity (uses overlay service if SYSTEM_ALERT_WINDOW allowed)
adb shell am start -a com.assistant.ui.action.OPEN_ASSISTANT \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.entry.VirtualAssistantActivity \
  --es face hybrid

# UI style gallery (debug / design review)
adb shell am start -a com.assistant.ui.action.OPEN_ASSISTANT_GALLERY \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.gallery.AssistantUiGalleryActivity

# Overlay service summon / stop (secondary path; needs SYSTEM_ALERT_WINDOW)
adb shell am startservice \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.assistant.ui.assistant.IMMERSIVE_SUMMON

adb shell am startservice \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.assistant.ui.assistant.IMMERSIVE_STOP
```

Primary production summon remains the **Voice Interaction** path (wake word / assistant button). That session hosts Compose by default inside `AssistantSession`.

---

## Example vehicle profiles

OEM A — Compose hybrid face:

```bash
adb shell settings put global vehicle_assistant_ui compose
adb shell settings put global design_assistant_face hybrid
```

OEM B — Compose fusion face:

```bash
adb shell settings put global vehicle_assistant_ui compose
adb shell settings put global design_assistant_face fusion
```

OEM C — temporary XML Polestar plate:

```bash
adb shell settings put global vehicle_assistant_ui xml:polestar
```

Demo / clean chrome (hide debug strip, short idle close):

```bash
adb shell settings put global vehicle_assistant_ui compose
adb shell settings put global design_assistant_face hybrid
adb shell settings put global vehicle_assistant_debug_strip off
adb shell settings put global vehicle_assistant_idle_timeout_sec 5
```

After changing Global settings, summon the assistant again (or restart the app process) so the session reinflates.

---

## Host wiring (developers)

Installed from `VehicleApplication`:

```kotlin
AssistantRuntimeBootstrap.install(this, useDemoBackend = true)
```

| Class | Path | Role |
|-------|------|------|
| `AssistantRuntimeBootstrap` | `app/.../assistant/` | Install host + backend + UI/face defaults |
| `VehicleAssistantHost` | same | `AssistantHost` cabin + cluster hand-off |
| `AssistantUiProfile` | same | Compose vs XML profile |
| `VehicleAgentAssistantBackend` | same | Production bridge skeleton to `AssistantViewModel` |
| `DemoAssistantBackend` | `:assistant` | Current Compose event driver |

To switch Compose onto the real agent:

```kotlin
AssistantRuntimeBootstrap.install(this, useDemoBackend = false)
```

(`VehicleAgentAssistantBackend` attaches when `VehicleAgentService` binds.)

---

## Toolchain notes

- AGP **8.7.3**, Gradle **8.9**, Kotlin **2.2**, Compose BOM **2025.12.01**
- Material3 Expressive (`MaterialShapes`) requires `material3:1.5.0-alpha04`
- Gradle JVM must be JDK 17–21 (`buildDeploy.ps1` auto-selects Android Studio JBR when possible)
- Emulator images are typically `x86_64`; APK includes `arm64-v8a` + `x86_64`

---

## Roadmap (intentional follow-ups)

1. Flip default backend from Demo → `VehicleAgentAssistantBackend` once STT/TTS event mapping is validated end-to-end.
2. Remove XML layouts after OEM sign-off on Compose.
3. Optional later: ADB tokens for full gallery chrome styles (`VoicePlate`, `SideRail`, …) beyond face + compose/xml.
4. Optional later: rename `com.assistant.ui.*` packages into `com.tcs.vehicleassistant.*`.

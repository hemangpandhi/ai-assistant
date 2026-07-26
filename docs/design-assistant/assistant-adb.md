# Assistant — adb commands

Translucent immersive assistant over home / any app (when overlay is allowed).

## Prerequisites

```bash
# Install (or reinstall) debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Required for translucent overlay + voice
adb shell appops set com.test.design SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.test.design android.permission.RECORD_AUDIO
```

Or use the install helper (grants both):

```bash
.cursor/scripts/install-apk.sh
```

## Launch assistant

Brings `MainActivity` under the glass, then shows the translucent immersive overlay:

```bash
adb shell am start -a com.test.design.action.OPEN_ASSISTANT \
  -n com.test.design/.presentation.assistant.VirtualAssistantActivity
```

With a face override (persists):

```bash
adb shell am start -a com.test.design.action.OPEN_ASSISTANT \
  -n com.test.design/.presentation.assistant.VirtualAssistantActivity \
  --es face fusion
```

Helper script (opens main first, then assistant):

```bash
.cursor/scripts/launch-assistant.sh
```

### Summon again (overlay already running)

```bash
adb shell am startservice \
  -n com.test.design/.presentation.assistant.ImmersiveAssistantOverlayService \
  -a com.test.design.assistant.IMMERSIVE_SUMMON
```

### Stop overlay

```bash
adb shell am startservice \
  -n com.test.design/.presentation.assistant.ImmersiveAssistantOverlayService \
  -a com.test.design.assistant.IMMERSIVE_STOP
```

## Swap face (live)

Tokens: `none` | `eyes` | `glow` | `hybrid` | `eporo` | `fusion` | `fusionglow` | `fusioneyes` | `droid` | `glyph`

Aliases: `off`/`noface` → none · `immersive` → eyes · `aura`/`ring`/`purple_eyes` → glow · `immersive_hybrid`/`glow_hybrid` → hybrid · `eporp` → eporo · `express` → fusion · `fusion_glow`/`glow_fusion` → fusionglow · `fusion_eyes`/`fusion_black` → fusioneyes · `classic` → glyph

```bash
# EPORO robot head
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face eporo

# Fusion — EPORO glow eyes + Immersive expressions
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face fusion

# Fusion glow — Fusion shell + Immersive-glow capsule eyes
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face fusionglow

# Fusion eyes — Fusion shell + classic Immersive pale capsule eyes
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face fusioneyes

# Immersive eyes
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face eyes

# Immersive glow — same Immersive face with EPORO purple rings
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face glow

# Transcript only (no face)
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver \
  --es face none

# Log current face (logcat tag AssistantFace)
adb shell am broadcast -a com.test.design.action.GET_ASSISTANT_FACE \
  -n com.test.design/.presentation.assistant.AssistantFaceReceiver
adb logcat -d -s AssistantFace:I | tail -n 3
```

Settings.Global (survives process; observed live when app is up):

```bash
adb shell settings put global design_assistant_face eyes
adb shell settings get global design_assistant_face
```

Default when nothing is saved: **Immersive eyes** (`eyes`). Override anytime with the face tokens above. Size matches Weather sink (~10% larger face).

## Other assistant surfaces

```bash
# UI style gallery
adb shell am start -a com.test.design.action.OPEN_ASSISTANT_GALLERY \
  -n com.test.design/.presentation.assistant.gallery.AssistantUiGalleryActivity

# AAOS capsule overlay bootstrap
adb shell am start -a com.test.design.action.START_ASSISTANT_OVERLAY \
  -n com.test.design/.presentation.assistant.overlay.AssistantOverlayBootstrapActivity
```

## Voice (hotword)

Mic listening runs **after** the assistant overlay/activity is open. Say:

- “Hey assistant”
- “Hi assistant”
- “Okay assistant”
- “Assistant”

There is no always-on background hotword yet — open the assistant first (dock sparkles icon, or the launch command above).

## Emulator note

- Window blur-behind (`FLAG_BLUR_BEHIND` / `windowBlurBehindEnabled`) is disabled for the immersive overlay **and** Assistant Gallery theme — it crashes SurfaceFlinger on emulators (Pixel Tablet GPU is fine). Compose scrim still dims.
- Do **not** rely on cold-starting Main under the overlay; OsmDroid + overlay can use ~200MB GL and get the process killed when speaking starts. Open home first (or use `launch-assistant.sh`).
- Overlay TTS is off by default (silent lip-sync); wake/dismiss play flat melodic chimes plus haptic — entry rises G–B–D, exit falls A–E.

## In-app

From `MainActivity`, the floating dock **AutoAwesome** (purple sparkles) / mic icons call `ImmersiveAssistantOverlayService.show()`.

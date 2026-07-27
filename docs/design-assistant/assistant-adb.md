# Assistant — adb commands (VehicleEdge)

Host package: `com.tcs.vehicleassistant`

Default UI: **Compose immersive** with face **hybrid** (immersive hybrid).  
Legacy XML voice plates remain available via UI profile.

## Prerequisites

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Optional: translucent overlay service path
adb shell appops set com.tcs.vehicleassistant SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.tcs.vehicleassistant android.permission.RECORD_AUDIO
```

Or use:

```powershell
.\buildDeploy.ps1
```

## UI renderer profile (Compose vs XML)

```bash
# Compose immersive (default)
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
  -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver \
  --es ui compose

# Legacy XML voice plate (Polestar / pill / side / top / immersive / hud / beveled / cinematic)
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_ASSISTANT_UI \
  -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver \
  --es ui xml:polestar

adb shell am broadcast -a com.tcs.vehicleassistant.action.GET_ASSISTANT_UI \
  -n com.tcs.vehicleassistant/.assistant.AssistantUiProfileReceiver
adb logcat -d -s AssistantUi:I | tail -n 3

# Survives process
adb shell settings put global vehicle_assistant_ui compose
adb shell settings get global vehicle_assistant_ui
```

Tokens: `compose` | `immersive` | `xml` | `xml:polestar` | `xml:pill` | `xml:side` | `xml:top` | `xml:immersive` | `xml:hud` | `xml:beveled` | `xml:cinematic`

## Swap face (live)

Tokens: `none` | `eyes` | `glow` | `hybrid` | `eporo` | `fusion` | `fusionglow` | `fusioneyes` | `droid` | `glyph`

Default on first install: `hybrid`

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

## Debug strip (model / backend / live log)

On debuggable builds the immersive overlay shows a top debug strip. Hide or restore it:

```bash
# Hide
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver \
  --es visible off

# Show
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_DEBUG_STRIP \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver \
  --es visible on

adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_DEBUG_STRIP \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.api.AssistantDebugStripReceiver
adb logcat -d -s AssistantDebugStrip:I | tail -n 3

# Survives process
adb shell settings put global vehicle_assistant_debug_strip off
adb shell settings get global vehicle_assistant_debug_strip
```

Tokens: `on` | `off` | `1` | `0` | `show` | `hide` | `true` | `false`  
Default: `on` (strip still only appears on debuggable APKs).

## Idle timeout (auto-close overlay)

Quiet-listening auto-close while the Compose overlay is open. Default: **5** seconds. Use `0` to disable.

```bash
# Set to 5 seconds
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_IDLE_TIMEOUT \
  -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver \
  --ei sec 5

# Disable
adb shell am broadcast -a com.tcs.vehicleassistant.action.SET_IDLE_TIMEOUT \
  -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver \
  --ei sec 0

adb shell am broadcast -a com.tcs.vehicleassistant.action.GET_IDLE_TIMEOUT \
  -n com.tcs.vehicleassistant/.assistant.AssistantIdleTimeoutReceiver
adb logcat -d -s AssistantIdle:I | tail -n 3

# Survives process
adb shell settings put global vehicle_assistant_idle_timeout_sec 5
adb shell settings get global vehicle_assistant_idle_timeout_sec
```

Range: `0`–`600` seconds.

## Launch assistant surfaces

```bash
# Standalone Compose activity (prefers overlay service if SYSTEM_ALERT_WINDOW allowed)
adb shell am start -a com.assistant.ui.action.OPEN_ASSISTANT \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.entry.VirtualAssistantActivity \
  --es face hybrid

# UI style gallery
adb shell am start -a com.assistant.ui.action.OPEN_ASSISTANT_GALLERY \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.gallery.AssistantUiGalleryActivity

# Summon / stop immersive overlay service
adb shell am startservice \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.assistant.ui.assistant.IMMERSIVE_SUMMON

adb shell am startservice \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.assistant.ui.assistant.IMMERSIVE_STOP

# Optional: set face while summoning the overlay service
adb shell am startservice \
  -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.assistant.ui.assistant.SET_FACE \
  --es face hybrid
```

Wake-word / home-button VIS session uses the same Compose immersive UI by default (hosted in `AssistantSession`).

### Quick presets

```bash
# Compose + hybrid face + hide debug strip + 5s idle close
adb shell settings put global vehicle_assistant_ui compose
adb shell settings put global design_assistant_face hybrid
adb shell settings put global vehicle_assistant_debug_strip off
adb shell settings put global vehicle_assistant_idle_timeout_sec 5
adb shell am force-stop com.tcs.vehicleassistant
```

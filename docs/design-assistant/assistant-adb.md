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
adb shell am broadcast -a com.test.design.action.SET_ASSISTANT_FACE \
  -n com.tcs.vehicleassistant/com.test.design.assistant.face.AssistantFaceReceiver \
  --es face hybrid

adb shell am broadcast -a com.test.design.action.GET_ASSISTANT_FACE \
  -n com.tcs.vehicleassistant/com.test.design.assistant.face.AssistantFaceReceiver
adb logcat -d -s AssistantFace:I | tail -n 3

adb shell settings put global design_assistant_face hybrid
adb shell settings get global design_assistant_face
```

## Launch assistant surfaces

```bash
# Standalone Compose activity (prefers overlay service if SYSTEM_ALERT_WINDOW allowed)
adb shell am start -a com.test.design.action.OPEN_ASSISTANT \
  -n com.tcs.vehicleassistant/com.test.design.assistant.entry.VirtualAssistantActivity

# UI style gallery
adb shell am start -a com.test.design.action.OPEN_ASSISTANT_GALLERY \
  -n com.tcs.vehicleassistant/com.test.design.assistant.ui.gallery.AssistantUiGalleryActivity

# Summon / stop immersive overlay service
adb shell am startservice \
  -n com.tcs.vehicleassistant/com.test.design.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.test.design.assistant.IMMERSIVE_SUMMON

adb shell am startservice \
  -n com.tcs.vehicleassistant/com.test.design.assistant.ui.immersive.ImmersiveAssistantOverlayService \
  -a com.test.design.assistant.IMMERSIVE_STOP
```

Wake-word / home-button VIS session uses the same Compose immersive UI by default (hosted in `AssistantSession`).

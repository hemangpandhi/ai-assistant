# Emotions & face cues — ADB test guide

Host package: `com.tcs.vehicleassistant`

Two independent ADB preview overlays drive the face on the Compose immersive stage:

| Layer | What it changes | Receiver | Preview store |
|-------|-----------------|----------|---------------|
| **Mood** (emotions) | Eye pose, mouth curve/open, blush, glow, blink, gaze | `AssistantMoodReceiver` | `AssistantMoodPreview` |
| **Face cues** (topic icons) | Material icons in eye / mouth / cheek slots | `AssistantFaceCueReceiver` | `AssistantFaceCuePreview` |

While a preview is set, it **overrides** session / LLM values. Clear restores pipeline mood and LLM cues (or geometry when empty).

**SET auto-opens** the immersive stage (overlay service / VIS composition) and **holds it open** while any mood or face-cue preview is active — idle timeout and SessionComplete will not dismiss it. Close with the overlay dismiss control, `IMMERSIVE_STOP`, or clear both previews and dismiss normally.

General UI / face / placement commands: [assistant-adb.md](./assistant-adb.md).

All commands below are **one-liners** (copy/paste as-is).

---

## Prerequisites

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.tcs.vehicleassistant SYSTEM_ALERT_WINDOW allow
adb shell settings put global vehicle_assistant_ui compose
adb shell settings put global design_assistant_face hybrid
```

SET broadcasts summon and hold the immersive overlay by default. Pass `--ez summon false` to change preview without opening it.

```bash
adb logcat -s AssistantMood:I AssistantFaceCue:I
```

---

## Moods (emotions)

Component: `com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver`

### Set / get / clear

```bash
# Set affective mood (summons overlay)
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood triumph

# Alias extra key
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es expression happy

# Preview only (overlay already open)
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood excited --ez summon false

# Read current preview
adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver
adb logcat -d -s AssistantMood:I | tail -n 3

# Clear → back to pipeline / LLM mood
adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver

# Also clears via SET: --es mood off|clear|none
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood clear
```

### Suggested walkthrough

Step through families and watch pose / glow / blink on the hybrid face:

```bash
# Pipeline
for m in idle listening speaking thinking reading searching; do adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood "$m" --ez summon false; sleep 2; done

# Happiness / high energy
for m in happy amused joyous excited jubilation triumph gratitude contentment proud; do adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood "$m" --ez summon false; sleep 2; done

# Soft / rest / concern
for m in relaxed shy drowsy tired sleeping doubt concerned sad bored; do adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood "$m" --ez summon false; sleep 2; done
```

### Mood tokens

Case-insensitive; match `AssistantMood` names (or labels).

**Pipeline:** `idle` `listening` `speaking` `thinking` `reading` `searching`

**Attraction / engagement:** `attraction` `admiration` `desire` `interest` `surprise` `astonishment`

**Happiness:** `happy` `amused` `joyous` `excited` `jubilation` `gratitude` `contentment` `proud` `triumph`

**Soft / rest:** `relaxed` `shy` `acceptance` `complicity`

**Focus / sleep / concern:** `concentration` `dreamy` `drowsy` `tired` `sleeping` `doubt` `concerned` `impressed`

**Other affective:** `sad` `bored`

---

## Face cues (topic icons)

Component: `com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver`

Null / omitted / `none` keeps geometric eyes / mouth for that slot. Accents sit on the cheeks (between eyes and mouth).

### Per-slot

```bash
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver --es left_eye sunny --es right_eye sunny --es mouth music --es left_accent sparkle --es right_accent star
```

### LLM-style `<face …/>` tag

```bash
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver --es face '<face left_eye="sunny" right_eye="rain" mouth="music" left_accent="sparkle" right_accent="none"/>'
```

Extra key alias: `--es tag '…'` (same as `face`).

### Named presets

```bash
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver --es preset weather
```

| Preset | Approx. slots |
|--------|----------------|
| `weather` / `sunny` | sunny eyes + cloudy mouth |
| `rain` | rain eyes + storm mouth |
| `music` | music mouth (+ accents from tool mapper) |
| `search` | search eyes |
| `climate` / `hvac` / `ac` | thermostat mouth + ac / heat accents |
| `sparkle` / `excited` | sparkle / star cheek accents |
| `nav` / `navigate` | navigate mouth |
| `clear` / `off` / `none` / `reset` | clear preview |

### Get / clear

```bash
adb shell am broadcast -a com.assistant.ui.action.GET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver
adb logcat -d -s AssistantFaceCue:I | tail -n 3
adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver
```

### Icon tokens

`rain` `storm` `snow` `cloudy` `sunny`  
`thermostat` `ac` `heat` `fan` `defrost`  
`music` `podcast` `mic`  
`search` `navigate`  
`sparkle` `star` `wave` `heart`

### Cue walkthrough

```bash
for p in weather rain music search climate sparkle nav; do adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver --es preset "$p" --ez summon false; sleep 2; done
```

---

## Mood + cues together

Previews stack: mood morphs geometry; cues replace slots with icons.

```bash
# Happy weather glance
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood happy
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver --es preset weather --ez summon false

# Excited music
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver --es mood excited --ez summon false
adb shell am broadcast -a com.assistant.ui.action.SET_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver --es preset music --ez summon false

# Reset both
adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver
adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver
```

---

## Gallery (visual catalog)

```bash
adb shell am start -a com.assistant.ui.action.OPEN_ASSISTANT_GALLERY -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.gallery.AssistantUiGalleryActivity
```

---

## Explicit close

```bash
# Clear previews (releases hold; overlay may stay until idle / dismiss)
adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_MOOD -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantMoodReceiver
adb shell am broadcast -a com.assistant.ui.action.CLEAR_ASSISTANT_FACE_CUES -n com.tcs.vehicleassistant/com.assistant.ui.assistant.face.AssistantFaceCueReceiver

# Stop standalone overlay service
adb shell am startservice -n com.tcs.vehicleassistant/com.assistant.ui.assistant.ui.immersive.ImmersiveAssistantOverlayService -a com.assistant.ui.assistant.IMMERSIVE_STOP
```

Dismissing the stage (tap/back/stop) also clears both ADB previews.

## Notes

- Previews are **process-wide in-memory** — they do not survive `am force-stop` / process death. Settings keys for face / UI still do.
- Unknown mood / preset / icon logs a warning on `AssistantMood` / `AssistantFaceCue` and leaves the previous preview unchanged (unless clear).
- Live session moods still go through `FaceMoodResolver` (Listening / Thinking / Searching / Reading beat affective). ADB mood preview bypasses that resolver for the overlay.
- For production LLM tags (not ADB): `<mood>happy</mood>` and `<face left_eye="sunny" …/>` — see `MoodTagParser` / `FaceCueParser` / `FaceCueCatalog`.

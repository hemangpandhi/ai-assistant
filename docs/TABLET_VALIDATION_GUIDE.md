# Tablet validation guide (slim APK + sideloaded models)

Practical setup for **another** AAOS / car-user tablet running Vehicle Assistant.

- **Package:** `com.tcs.vehicleassistant`
- **Launcher activity:** `com.tcs.vehicleassistant/.LocalLLMActivity`
- **Default wake word:** `hey assistant`
- **Default VIS:** `com.tcs.vehicleassistant/.AssistantVoiceInteractionService`
- **Typical AAOS user:** `10` (not user 0)
- **Slim debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (~**190 MiB**)
- **Models:** not in the APK — push separately under `/data/local/tmp/`
- **Java / JDK:** required only on the **host** to build; **not** required on the tablet

Also see: [MODEL_SIDELOAD.md](MODEL_SIDELOAD.md), [manual_llm_deployment_guide.md](manual_llm_deployment_guide.md).

---

## 1. Prerequisites

On the **host** (dev machine):

1. `adb` installed and working
2. Tablet USB debugging enabled / authorized
3. Device online: `adb devices` shows the serial
4. Repo checkout with:
   - Built slim APK (or a known good `app-debug.apk` ~190 MiB)
   - Gitignored `device_models/` (STT + Vosk)
   - LLM file `gemma-4-E2B-it.litertlm` (~2.5 GiB) available locally (often at repo root or shared model store)

On the **tablet**: no JDK, no Gradle — only the APK + sideloaded files.

Set variables once (copy-paste friendly):

```bash
export SERIAL=<device-serial>   # e.g. 3704105H8094TU
export USER_ID=10              # AAOS passenger / car user
export REPO=/path/to/ai-assistant   # this git repo root
cd "$REPO"
```

Confirm:

```bash
adb -s "$SERIAL" get-state
adb -s "$SERIAL" shell am get-current-user   # often 10 on AAOS tablets
```

---

## 2. What lives where

| Location | Contents |
| --- | --- |
| **APK (~190 MiB)** | App code, LiteRT runtime, **TTS** `sherpa-onnx-tts/` (~79 MiB), Silero VAD, vision `.task` / `.tflite` |
| **Device `/data/local/tmp/stt/`** | Whisper STT ONNX (required; **not** in APK) |
| **Device `/data/local/tmp/vosk/`** | Vosk wake pack with `am/` (required; **not** in APK) |
| **Device `/data/local/tmp/llm/`** | LiteRT-LM `.litertlm` (required for on-device LLM) |

**No assets fallback** for STT or Vosk: missing sideloads → STT / wake stay unavailable (error in logcat).

### Required pushes (must)

| Local path | Device path | Purpose |
| --- | --- | --- |
| `device_models/stt/tiny.en-encoder.int8.onnx` | `/data/local/tmp/stt/tiny.en-encoder.int8.onnx` | Whisper Tiny encoder |
| `device_models/stt/tiny.en-decoder.int8.onnx` | `/data/local/tmp/stt/tiny.en-decoder.int8.onnx` | Whisper Tiny decoder |
| `device_models/stt/tiny.en-tokens.txt` | `/data/local/tmp/stt/tiny.en-tokens.txt` | Whisper tokens |
| `device_models/vosk/.` (full tree) | `/data/local/tmp/vosk/` | Wake word (must contain `am/`) |
| `gemma-4-E2B-it.litertlm` (or shared copy) | `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` | Default edge LLM |

Paths match `AssistantConfig`: `STT_SIDELOAD_DIR=/data/local/tmp/stt`, Vosk under `/data/local/tmp/vosk/`, `DEFAULT_MODEL_PATH=/data/local/tmp/llm/gemma-4-E2B-it.litertlm`.

### Optional pushes

| Local | Device | Notes |
| --- | --- | --- |
| `base.en-encoder.int8.onnx` / `base.en-decoder.int8.onnx` / `base.en-tokens.txt` | `/data/local/tmp/stt/` | Higher-quality STT; preferred over tiny if present |
| Other `*.litertlm` | `/data/local/tmp/llm/` | Selectable in-app; default remains Gemma 4 E2B IT |
| TTS voice packs | `/data/local/tmp/tts/<id>/` | Only if using extra voices (see `TtsVoiceCatalog`) |

### Still in the APK (do **not** push for basic TTS)

- `sherpa-onnx-tts/` (~79 MiB) — cabin TTS ships in the slim APK
- Silero VAD, vision assets

---

## 3. Sideload models (step-by-step)

```bash
adb -s "$SERIAL" shell mkdir -p /data/local/tmp/stt /data/local/tmp/vosk /data/local/tmp/llm

# --- STT (Whisper Tiny int8) — required ---
adb -s "$SERIAL" push device_models/stt/tiny.en-encoder.int8.onnx /data/local/tmp/stt/
adb -s "$SERIAL" push device_models/stt/tiny.en-decoder.int8.onnx /data/local/tmp/stt/
adb -s "$SERIAL" push device_models/stt/tiny.en-tokens.txt /data/local/tmp/stt/

# --- Vosk wake pack — required (entire tree; must include am/) ---
adb -s "$SERIAL" push device_models/vosk/. /data/local/tmp/vosk/

# --- LLM — required for on-device Q&A / tools ---
# Adjust source path if your copy is elsewhere (e.g. ../model/)
adb -s "$SERIAL" push gemma-4-E2B-it.litertlm /data/local/tmp/llm/

# --- Permissions (app user must be able to read) ---
adb -s "$SERIAL" shell chmod 755 /data/local/tmp/stt /data/local/tmp/vosk /data/local/tmp/llm
adb -s "$SERIAL" shell chmod 644 /data/local/tmp/stt/*
adb -s "$SERIAL" shell chmod -R a+rX /data/local/tmp/vosk
adb -s "$SERIAL" shell chmod 644 /data/local/tmp/llm/*.litertlm
```

**Verify on device:**

```bash
adb -s "$SERIAL" shell ls -la /data/local/tmp/stt/
adb -s "$SERIAL" shell ls -la /data/local/tmp/vosk/am/
adb -s "$SERIAL" shell ls -lh /data/local/tmp/llm/
```

Expect at least:

- STT: `tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx`, `tiny.en-tokens.txt`
- Vosk: `am/final.mdl` (and siblings under `conf/`, `graph/`, `ivector/`)
- LLM: `gemma-4-E2B-it.litertlm` (~2.5 GiB)

---

## 4. Install APK + mic + default assistant

```bash
APK=app/build/outputs/apk/debug/app-debug.apk

adb -s "$SERIAL" install -r --user "$USER_ID" "$APK"

# Microphone (required for wake + STT)
adb -s "$SERIAL" shell pm grant --user "$USER_ID" \
  com.tcs.vehicleassistant android.permission.RECORD_AUDIO

# Default Voice Interaction Service (AAOS / multi-user)
adb -s "$SERIAL" shell settings put --user "$USER_ID" secure voice_interaction_service \
  com.tcs.vehicleassistant/.AssistantVoiceInteractionService
adb -s "$SERIAL" shell settings put --user "$USER_ID" secure assistant \
  com.tcs.vehicleassistant/.AssistantVoiceInteractionService

# Launch
adb -s "$SERIAL" shell am start --user "$USER_ID" \
  -n com.tcs.vehicleassistant/.LocalLLMActivity
```

If the UI prompts for mic, accept once. Confirm:

```bash
adb -s "$SERIAL" shell settings get --user "$USER_ID" secure voice_interaction_service
adb -s "$SERIAL" shell cmd appops get --user "$USER_ID" com.tcs.vehicleassistant RECORD_AUDIO
```

---

## 5. Smoke validation checklist

Wait for engine init (first load can take minutes while kernels compile). In logcat look for:

```text
Engine ready: model=... backend=...
```

```bash
adb -s "$SERIAL" logcat -d | grep -E 'Engine ready|WakeWord|STT|Vosk model'
```

Then exercise:

| Check | How | Pass criteria |
| --- | --- | --- |
| Wake word | Say **“hey assistant”** | Overlay / session opens; log: `Wake word matched` |
| HVAC AC | “Turn on the AC” / “Turn off the AC” | AC changes; spoken/UI ack is specific (not always the generic line) |
| Media | “Play music” | Music app / playback starts |
| Simple Q&A | “What time is it?” or a short fact question | Coherent spoken/text answer |
| Engine | logcat after launch | `Engine ready` with Gemma path under `/data/local/tmp/llm/` |

### Known issue to watch

Generic tool ack investigation may still be open. **Verify** cabin actions do **not** always answer only:

> “Done — that's taken care of.”

Prefer tool-specific feedback (e.g. AC on/off, media started). If every tool reply is that exact generic string, flag it before demo sign-off.

---

## 6. Size expectations

| Artifact | Approx size | Notes |
| --- | --- | --- |
| Slim `app-debug.apk` | ~**190 MiB** | Includes TTS; excludes Whisper + Vosk |
| Whisper Tiny int8 (sideload) | ~100 MiB | encoder+decoder+tokens |
| Vosk pack (sideload) | ~**205 MiB** | full tree with `am/` |
| `gemma-4-E2B-it.litertlm` | ~**2.5 GiB** | under `/data/local/tmp/llm/` |
| TTS in APK | ~**79 MiB** | no separate push for default TTS |

Models stay on device storage; reinstalling the APK does **not** remove `/data/local/tmp/` sideloads (unless the device wipes that tree).

---

## 7. Quick one-shot (after variables set)

```bash
adb -s "$SERIAL" shell mkdir -p /data/local/tmp/stt /data/local/tmp/vosk /data/local/tmp/llm
adb -s "$SERIAL" push device_models/stt/tiny.en-encoder.int8.onnx /data/local/tmp/stt/
adb -s "$SERIAL" push device_models/stt/tiny.en-decoder.int8.onnx /data/local/tmp/stt/
adb -s "$SERIAL" push device_models/stt/tiny.en-tokens.txt /data/local/tmp/stt/
adb -s "$SERIAL" push device_models/vosk/. /data/local/tmp/vosk/
adb -s "$SERIAL" push gemma-4-E2B-it.litertlm /data/local/tmp/llm/
adb -s "$SERIAL" shell 'chmod 755 /data/local/tmp/stt /data/local/tmp/vosk /data/local/tmp/llm; chmod 644 /data/local/tmp/stt/*; chmod -R a+rX /data/local/tmp/vosk; chmod 644 /data/local/tmp/llm/*.litertlm'
adb -s "$SERIAL" install -r --user "$USER_ID" app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell pm grant --user "$USER_ID" com.tcs.vehicleassistant android.permission.RECORD_AUDIO
adb -s "$SERIAL" shell settings put --user "$USER_ID" secure voice_interaction_service com.tcs.vehicleassistant/.AssistantVoiceInteractionService
adb -s "$SERIAL" shell settings put --user "$USER_ID" secure assistant com.tcs.vehicleassistant/.AssistantVoiceInteractionService
adb -s "$SERIAL" shell am start --user "$USER_ID" -n com.tcs.vehicleassistant/.LocalLLMActivity
```

Do **not** commit `device_models/` or `*.litertlm` binaries to git.

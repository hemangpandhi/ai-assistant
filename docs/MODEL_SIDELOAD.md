# On-device model sideload (`/data/local/tmp/`)

Large STT (Whisper) and wake (Vosk) models are **not packaged in the APK** and are **not** tracked with Git LFS. Push them onto the device and let the app load from the filesystem.

**APK no longer contains:**

- `sherpa-onnx-whisper/` (~135 MB Whisper Tiny ONNX) — required at `/data/local/tmp/stt/`
- `model/` (~205 MB Vosk wake pack) — required at `/data/local/tmp/vosk/`

**Still in the APK (for now):** `sherpa-onnx-tts/` (~79 MB), Silero VAD, vision `.task` / `.tflite` assets.

Local copies for pushing live under gitignored `device_models/`.

## Layout

```
/data/local/tmp/llm/*.litertlm
/data/local/tmp/stt/tiny.en-encoder.int8.onnx
/data/local/tmp/stt/tiny.en-decoder.int8.onnx
/data/local/tmp/stt/tiny.en-tokens.txt
# optional higher quality STT:
/data/local/tmp/stt/base.en-encoder.int8.onnx
/data/local/tmp/stt/base.en-decoder.int8.onnx
/data/local/tmp/stt/base.en-tokens.txt
# required Vosk wake pack (extracted tree): /data/local/tmp/vosk/  (must contain am/)
# optional TTS voices: /data/local/tmp/tts/<id>/  (see TtsVoiceCatalog)
# optional Sherpa KWS: /data/local/tmp/kws/
```

**Load order (STT):** `base` under `/data/local/tmp/stt/` → else `tiny` under `/data/local/tmp/stt/`. No assets fallback — missing files log an error and STT stays unavailable.

**Wake (Vosk):** `/data/local/tmp/vosk/` only (directory must contain `am/`). No assets fallback.

## adb push (required for STT + wake)

```bash
adb shell mkdir -p /data/local/tmp/stt /data/local/tmp/vosk /data/local/tmp/llm

# Whisper Tiny int8 (from device_models; not in APK)
adb push device_models/stt/tiny.en-encoder.int8.onnx /data/local/tmp/stt/
adb push device_models/stt/tiny.en-decoder.int8.onnx /data/local/tmp/stt/
adb push device_models/stt/tiny.en-tokens.txt /data/local/tmp/stt/

# Vosk wake pack (entire tree)
adb push device_models/vosk/. /data/local/tmp/vosk/

# LLM (see also manual_llm_deployment_guide.md):
# adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/
```

Ensure world-readable if the app user cannot open root-owned files:

```bash
adb shell chmod 644 /data/local/tmp/stt/*
adb shell chmod -R a+rX /data/local/tmp/vosk
adb shell chmod 755 /data/local/tmp/stt /data/local/tmp/vosk /data/local/tmp/llm
```

Multi-user devices (e.g. automotive user 10): install with `--user 10` after push.

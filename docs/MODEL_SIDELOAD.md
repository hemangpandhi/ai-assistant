# On-device model sideload (`/data/local/tmp/`)

Large models are **not** tracked with Git LFS. Push them onto the device and let the app load from the filesystem.

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
# optional TTS voices: /data/local/tmp/tts/<id>/  (see TtsVoiceCatalog)
# optional Vosk wake pack (extracted tree): /data/local/tmp/vosk/
# optional Sherpa KWS: /data/local/tmp/kws/
```

**Load order (STT):** `base` under `/data/local/tmp/stt/` → `tiny` under `/data/local/tmp/stt/` → assets `sherpa-onnx-whisper/` if present.

**Wake (Vosk):** `/data/local/tmp/vosk/` if it contains `am/` → else assets `model/` (unchanged).

## adb push examples

Local copies for pushing live under `device_models/` (gitignored) or the bundled whisper folder:

```bash
adb shell mkdir -p /data/local/tmp/stt /data/local/tmp/llm

# Preferred: int8 tiny from device_models (moved out of assets so they are not APK-packaged)
adb push device_models/stt/tiny.en-encoder.int8.onnx /data/local/tmp/stt/
adb push device_models/stt/tiny.en-decoder.int8.onnx /data/local/tmp/stt/
adb push device_models/stt/tiny.en-tokens.txt /data/local/tmp/stt/

# Or from the assets fallback pack still used when sideload is missing:
# adb push app/src/main/assets/sherpa-onnx-whisper/tiny.en-encoder.int8.onnx /data/local/tmp/stt/
# adb push app/src/main/assets/sherpa-onnx-whisper/tiny.en-decoder.int8.onnx /data/local/tmp/stt/
# adb push app/src/main/assets/sherpa-onnx-whisper/tiny.en-tokens.txt /data/local/tmp/stt/

# LLM (see also manual_llm_deployment_guide.md):
adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/
```

Ensure world-readable if the app user cannot open root-owned files:

```bash
adb shell chmod 644 /data/local/tmp/stt/*
adb shell chmod 755 /data/local/tmp/stt /data/local/tmp/llm
```

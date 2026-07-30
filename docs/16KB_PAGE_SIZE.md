# 16 KB page-size compatibility

Android 15+ devices require native `.so` libraries to use **16 KB ELF LOAD
alignment** and (when uncompressed) **16 KB zip alignment**.

## Device warning

`App compatibility` / `16KB compatible ELF alignment check failed` on a
**debuggable** install means at least one packaged `lib/arm64-v8a/*.so` still has
LOAD segment align `0x1000` (4 KB) instead of `≥ 0x4000` (16 KB).

## Fixes applied in this repo

| Setting | Value |
|---------|--------|
| AGP | **8.5.2** |
| Gradle | **8.7** |
| `packaging.jniLibs.useLegacyPackaging` | **`false`** (STORED + 16 KB zip-align) |
| CameraX | **1.4.2** |
| MediaPipe tasks-vision | **0.10.26** |
| Vosk | **0.3.75** |
| TFLite | **`com.google.ai.edge.litert:litert:1.4.0`** (replaces 4 KB TFLite 2.13) |
| Soniqo (`soniqo-speech.aar` / `libspeech_android.so`) | **Removed** — cabin STT/TTS uses Sherpa-ONNX; platform `RecognitionService` is a no-op stub |

## Post-rebuild verification (debug APK)

| Library | Align | Status |
|---------|-------|--------|
| CameraX / MediaPipe / Vosk / LiteRT / Sherpa / LiteRT-LM / ONNX / JNA | ≥16384 | **OK** |
| ZIP packaging of `.so` | STORED | **OK** |
| `libspeech_android.so` | — | **Not packaged** (Soniqo AAR removed) |

Re-run the check below after `assembleDebug` / `assembleRelease` to confirm no remaining
4 KB–aligned libraries. As of the Soniqo removal there should be **no known FAIL** entries
from `libspeech_android.so`.

## How to re-check

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
unzip -qo "$APK" 'lib/arm64-v8a/*.so' -d /tmp/apk16k
for so in /tmp/apk16k/lib/arm64-v8a/*.so; do
  align=$(readelf -lW "$so" | awk '$1=="LOAD"{a=$NF+0; if(a>m)m=a} END{print m+0}')
  ok=FAIL; [ "$align" -ge 16384 ] && ok=OK
  printf '%-45s %s (align=%s)\n' "$(basename "$so")" "$ok" "$align"
done
```

# Vehicle Assistant — Coverage Report

Date: 2026-07-30  
Repo: `/home/tcs/AI_Assistant/git`  
Device: `3704105H8094TU` (user **10**)  
Java: 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`)  
`TMPDIR=/home/tcs/tmp`

## Commands run

```bash
export TMPDIR=/home/tcs/tmp
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SERIAL=3704105H8094TU

# Unit + JaCoCo (project task)
./gradlew :app:testDebugUnitTest :app:jacocoDebugUnitTestReport --no-daemon

# Instrumented (full package under user 10; no GemmaRegression suite present)
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-build-cache
adb -s 3704105H8094TU install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb -s 3704105H8094TU install -r --user 10 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 3704105H8094TU shell am instrument -w --user 10 \
  -e package com.tcs.vehicleassistant \
  com.tcs.vehicleassistant.test/androidx.test.runner.AndroidJUnitRunner
```

JaCoCo task: `:app:jacocoDebugUnitTestReport` (AGP `enableUnitTestCoverage` + custom report; **unit-only** — instrumented Jacoco is not wired).

## Unit tests

| | Count |
|--|------:|
| **Passed** | **693** |
| Failed | 0 |
| Errors | 0 |
| Skipped | 0 |

Baseline before gap-fill additions: **668** passed / 0 failed.

## Instrumented tests

| | |
|--|--|
| **Result** | **395 run, 1 failure** (~85s) |
| Failure | `AssistantEndToEndUITest.executeAllCommandsVisually` — Espresso `InputManager.getInstance` missing on this AAOS image (not ANR/mic) |
| Passed | Handlers (HVAC/Media/Nav/Window), ContextGuard, DirectTool catalogue + registry matrix, ExhaustiveRegistry, PlayMusicArtist, ToolManagerPath, UseCaseScenario |
| GemmaRegression | **Not present** in `androidTest` — full non-LLM instrumented suite was run |

## JaCoCo line / branch (unit)

| Scope | Before (this run baseline) | After |
|-------|----------------------------|-------|
| **Whole `:app` module** | **14.68%** line / **13.53%** branch | **15.71%** line / **15.79%** branch |
| **`com.tcs.vehicleassistant.core`** | **80.60%** line / **60.44%** branch | **84.07%** line / **67.35%** branch |
| **`com.tcs.vehicleassistant.utils`** | **62.31%** line / **48.70%** branch | **79.23%** line / **65.28%** branch |
| **`com.tcs.vehicleassistant.handlers`** | **22.99%** line | **26.14%** line |

### Core classes (after)

| Class | Line % | Notes |
|-------|--------|-------|
| CabinSnapshot, ConfirmationPolicy, GpuBackendResolver, NavSessionState, ToolRetriever | **100%** | Pure logic |
| ContextGuard | **98.25%** | 2 residual lines |
| DirectToolResolver | **96.19%** | Edge placeholder / city paths |
| TtsVoiceCatalog | **83%** | `availableVoices`/`findById` Robolectric often **not attributed** |
| DeviceCapabilities | **73%** | OpenCL FS probe / Build.* |
| DebugBroadcasts | **10%** | register/unregister Robolectric attribution gap |
| CabinSnapshotReader | **0%** | AudioManager / MediaSession / VHAL |
| KernelCacheManager | **0% reported** | Robolectric tests exist; JaCoCo classloader miss |

### Other high-value after

| Class | Line % |
|-------|--------|
| FollowUpRouter | **100%** |
| VolumeLevelResolver | **100%** |
| ParameterParser / ToolExecutionResult | **100%** |
| WeatherApiClient | **86%** |
| ToolCallParser | **92%** |
| EmergencyAlarmManager | **0%** (ToneGenerator / Context) |

## Tests added

- [`app/src/test/java/com/tcs/vehicleassistant/core/CoverageGapFillTest.kt`](../../app/src/test/java/com/tcs/vehicleassistant/core/CoverageGapFillTest.kt)  
  - JVM: ContextGuard sensor/nav/arg branches, CabinSnapshot aliases, FollowUpRouter named dest/alarm, DirectTool volume/seat/airflow/city/amenity, VolumeLevelResolver edges, ParameterParser, Weather WMO codes, ToolCallParser JSON-without-args, ToolManager speakable keyword helper, TTS flat sideload + humanize  
  - Robolectric: Memory long-term facts, TTS `availableVoices`/`findById`, DebugBroadcasts register, DeviceCapabilities describe, DemoSettings apply, ToolManager resolveDirectHit smoke  

(+25 unit tests vs baseline; some Robolectric hits are **not** credited in JaCoCo HTML for Android-bound classes.)

## Report paths

- HTML: [`docs/reports/jacoco_html/index.html`](jacoco_html/index.html)  
- XML: [`docs/reports/jacocoDebugUnitTestReport.xml`](jacocoDebugUnitTestReport.xml)  
- Build copy: `app/build/reports/jacoco/jacocoDebugUnitTestReport/html/index.html`

## Honest limits — why not ~100% module

Whole-module **~16% line** is expected and **not** a failure of the suite:

1. **Activities / Services / UI** (`LocalLLMActivity`, `AssistantSession`, `WakeWordService` body, animation views) need device UI + audio lifecycle.  
2. **VHAL / Car APIs / binders** (`VehicleManager`, handler actuation paths) need the vehicle property stack.  
3. **Native / LiteRT / vision / mic** (`llm`, `vision`, `hardware`, `speech`) are not JVM-mockable meaningfully.  
4. **Instrumented Jacoco is not wired** — device tests raise confidence but do not feed this report.  
5. **Robolectric attribution gaps** — `KernelCacheManager`, `ToolManager` body, `MemoryManager` SharedPreferences helpers, `DebugBroadcasts.register` often show **0%** even when tests pass.

**Closer to 100% for what matters:** pure `core` decision logic is in the mid-80s% line (high-90s for ContextGuard / DirectTool / FollowUpRouter / CabinSnapshot). Claiming 100% of the Android app module would be dishonest.

## Failures remaining

- Unit: **none**  
- Instrumented: **1** — `AssistantEndToEndUITest` Espresso InputManager on AAOS Tangorpro car image  

No commit / push performed (per instructions).

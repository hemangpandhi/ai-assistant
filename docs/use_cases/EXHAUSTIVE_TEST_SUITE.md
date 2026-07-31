# Exhaustive use-case test suites

Sources of truth:
- `docs/use_cases/LLM_All_Use_Cases.md` (90 tools)
- `docs/use_cases/use-cases.md`, `WOW_USE_CASES.md`, `docs/demo_script.md`
- `app/src/main/assets/vehicle_skills_registry.json` (91 tools)

## JVM unit (fast, no device)

```bash
export TMPDIR=/home/tcs/tmp JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:testDebugUnitTest --tests 'com.tcs.vehicleassistant.requirements.*' --no-build-cache
```

Covers:
- Every DirectTool keyword → tool id (`ExhaustiveDirectToolUnitTest`)
- Registry ↔ MD coherence
- LLM_All_Use_Cases triggers vs registry / DirectTool
- Documented demo/use-case prompts + play-artist matrix
- Follow-up / affirmatives / numbered picks
- Driver-seat safety/routing matrix (`DriverSeatScenarioMatrixUnitTest`) — see [DRIVER_SEAT_TABLET_SUITE.md](DRIVER_SEAT_TABLET_SUITE.md)

## Instrumented on tablet (user 10)

```bash
export TMPDIR=/home/tcs/tmp ANDROID_SERIAL=3704105H8094TU
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-build-cache
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb install -r --user 10 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w --user 10 \
  -e package com.tcs.vehicleassistant.requirements \
  com.tcs.vehicleassistant.test/androidx.test.runner.AndroidJUnitRunner
```

Covers on-device:
- Full DirectTool keyword matrix
- Use-case / demo scenarios + follow-ups
- Play-artist regression
- ToolManager + CUSTOM_KOTLIN handler smoke (intent-intercepted)
- TTS catalog availability

## Intentionally out of scope (not full E2E guarantees)

- Live Gemma response text for every MD example
- Full ASR mic → STT accuracy per phrase
- Physical VHAL property writes on hardware without Car service
- Confirmation-gated destructive actions (unlock / trunk) auto-execute
- Cloud fallback / vision proactive WOW without cameras

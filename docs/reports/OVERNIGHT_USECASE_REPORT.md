# Overnight Use-Case Stability Report

**Timestamp (UTC):** 2026-07-29T18:11:45Z  
**Device:** `ANDROID_SERIAL=3704105H8094TU` (AOSP Tangorpro car), install/run `--user 10`  
**Base commit SHA:** `0162bdbabc1d8f3adc4818260a2a7be0d4a837a1` (overnight fixes are **uncommitted** working tree)  
**Logs:** `/home/tcs/tmp/overnight_usecase_20260729/`

---

## Executive summary

- **Unit (requirements + core + jacoco-dependent suite):** **642 passed / 0 failed / 0 skipped** — PASS  
- **Instrumented (`com.tcs.vehicleassistant.requirements`, user 10):** **376 passed / 0 failed** — PASS  
- **P0 ContextGuard confirm loop:** fixed with `skipGuard=true` on affirmative execute  
- **Weather / identity / nearby / news:** DirectTool + registry + handler paths fixed; unit-locked  
- **DirectTool catalogue:** **64** direct-executable tools (up from ~28) with collision-cleaned keywords  
- **JaCoCo:** unit report generated; **core line ~80.6%**, **whole module line ~11.7%** (honest — not 100% module)

---

## Summary counts

| Layer | Suites / classes | Tests | Failed | Skipped | Result |
|-------|------------------|-------|--------|---------|--------|
| Host JVM unit | 40+ classes (see table) | 642 | 0 | 0 | **PASS** |
| Device instrumented | 7 requirement classes | 376 | 0 | 0 | **PASS** |
| ContextGuard instrumented (subset) | 1 class | 6 | 0 | 0 | **PASS** |

---

## Suite results — unit

| Suite | Result | Notes |
|-------|--------|-------|
| `requirements.ExhaustiveDirectToolUnitTest` | PASS | 325 keyword→tool cases |
| `requirements.GoldenPathMatrixUnitTest` / `GoldenPathParameterizedTest` | PASS | Demo HVAC/media/nav/weather/identity |
| `requirements.RequirementsGapFillUnitTest` | PASS | Weather / identity / nearby / news |
| `requirements.StabilityRegressionUnitTest` | PASS | Nav “me to”, affirm/decline, skipGuard contract |
| `requirements.ExhaustiveFollowUpUnitTest` | PASS | Seat heater / gas / numbered picks |
| `requirements.DocumentedPromptMatrixUnitTest` | PASS | Docs prompts |
| `requirements.LlmAllUseCasesTriggerUnitTest` | PASS | MD triggers vs registry |
| `requirements.RegistryAndDocsCoherenceUnitTest` | PASS | MD↔registry |
| `core.ContextGuardTest` / `ContextGuardConfirmFlowTest` / `ContextGuardLoadAndEscalateTest` | PASS | Confirm/block/escalate/loadFromConfig |
| `core.ConfirmationPolicyTest` | PASS | Affirm / decline / OTHER |
| `core.DirectToolResolverTest` + amenity/city | PASS | CITY placeholder, interrogative soft-path |
| `core.CabinSnapshot*` / `NavSessionState` / `GpuBackendResolver` / `ToolRetriever` / `TtsVoiceCatalog*` | PASS | Pure core coverage boost |
| Other app unit (Memory, WakeWord, Config, …) | PASS | Included via jacoco `testDebugUnitTest` |

---

## Suite results — instrumented (user 10)

| Suite | Result | Approx tests |
|-------|--------|--------------|
| `ContextGuardInstrumentedTest` | PASS | 6 |
| `DirectToolCatalogueSanityInstrumentedTest` | PASS | 3 |
| `DirectToolRegistryInstrumentedTest` | PASS | ~300+ |
| `ExhaustiveRegistryInstrumentedTest` | PASS | 7 |
| `PlayMusicArtistInstrumentedTest` | PASS | 9 |
| `ToolManagerPathInstrumentedTest` | PASS | 5 |
| `UseCaseScenarioInstrumentedTest` | PASS | 23 |
| **Package total** | **PASS** | **376** |

---

## Use-case areas

| Area | Status | Notes |
|------|--------|-------|
| HVAC | PASS | Temp/fan/AC/climate/airflow DirectTool + golden paths |
| Media | PASS | Play/pause/stop/volume artist matrix |
| Nav | PASS | “Navigate me to…”, go to, take me to; PLACE_NAME extraction |
| Windows/Doors | PARTIAL | Open/close windows DirectTool; unlock/trunk **confirm/block** (policy) |
| ContextGuard | PASS | Confirm/block/escalate; **yes no longer re-asks** (`skipGuard`) |
| FollowUp | PASS | Affirmatives + numbered list picks |
| LLM-skip / DirectTool | PASS | 64 direct tools; soft `what/who` for weather/identity |
| Weather | **FIXED** | “what is the weather?” → `getWeather(here)` + web search handler |
| Identity | **FIXED** | “who are you” / “what model is this” → `answerVehicleIdentity` |
| Nearby / news | **FIXED** | `searchNearby` / `suggestNearbyPlaces` (Overpass) / `getNewsHighlights` |

---

## Requirements gap table

| Requirement / phrase | Status | Notes |
|----------------------|--------|-------|
| “what is the weather” / “What's the weather?” | **fixed** | Soft interrogative + trailing `?` strip; CITY→`here`; `LocationManager` city at execute |
| “weather in Tokyo” | **fixed** | CITY arg extraction |
| “who are you” / “what model is this” | **fixed** | DirectTool + handler reads `INFO_MODEL` |
| “hungry” / “gas station” / “nearby pizza” / “find nearby” | **fixed** | AMENITY extract + default `restaurant` |
| “latest news” / “today's news” | **fixed** | DirectTool → web search highlights |
| “suggest nearby places…” | **fixed** | Overpass tourism/attraction (was stub ask) |
| Demo HVAC / media / nav golden phrases | **implemented** | Golden path unit matrix |
| “turn off front defroster” / “warm seat” | **fixed** | Keywords aligned to MD triggers |
| “set airflow … face and feet” | **fixed** | Maps to `face and floor` |
| ContextGuard “yes” confirm loop | **fixed** | `completeDirectToolTurn(..., skipGuard=true)` |
| Decline “no thanks” / “yes no” | **fixed** | `ConfirmationPolicy` decline-first |
| Unlock doors / open trunk while moving | **intentional** | Confirm/block policies — not auto DirectTool |
| Fresh air / drowsy / evening macros | **intentional** | `requires_confirmation` |
| `callContact` / `sendText` / `remember` / free-form `search` | **still blocked (by design)** | Unsupported NAME/FACT/`search_term` placeholders for DirectTool; LLM path |
| `bookRestaurant` / `queryMemory` / `openApp` / `setAllWindowsPosition` | **still blocked** | Weak/glued keywords; keep LLM — not safe DirectTool overnight |
| `analyzeCabinState` (camera) | **still blocked** | Needs vision/camera hardware |
| Agentic-only (alternate route, airport trip, …) | **still blocked** | `requires_agentic_loop` / network agent loops |
| Live Gemma text for every MD line | **out of scope** | Not full E2E LLM understanding |
| Perfect ASR | **out of scope** | Phrase tests are text→DirectTool |
| Cloud / vision WOW full E2E | **out of scope** | No camera/cloud overnight |

---

## JaCoCo coverage

| Scope | Line % | Instruction % | Branch % |
|-------|--------|---------------|----------|
| **`com.tcs.vehicleassistant.core`** | **80.59%** | **77.75%** | **61.21%** |
| **Whole `:app` module (unit)** | **11.69%** | **13.60%** | *(see XML)* |

**Report paths**

- HTML: [`docs/reports/jacoco_html/index.html`](jacoco_html/index.html)  
- XML: [`docs/reports/jacocoDebugUnitTestReport.xml`](jacocoDebugUnitTestReport.xml)  
- Build copy: `app/build/reports/jacoco/jacocoDebugUnitTestReport/html/index.html`  
- Command: `./gradlew :app:jacocoDebugUnitTestReport` (TMPDIR=/home/tcs/tmp, Java 17)

**Core vs 100% target (honest)**

- Pure decision code is largely covered: `DirectToolResolver` ~95% line, `ContextGuard` ~92% line after load/escalate tests, `ConfirmationPolicy`, `GpuBackendResolver`, `NavSessionState`, fuel normalize helper.  
- Remaining core misses are **Android/device-bound**, not overnight-mockable without Robolectric/device:
  - `CabinSnapshotReader` (AudioManager / MediaSession / VHAL) — **0%**
  - `KernelCacheManager` (Robolectric tests exist; JaCoCo often does not attribute Robolectric classloader hits) — **0% reported**
  - `DebugBroadcasts.register/unregister` (Context receivers)  
  - `DeviceCapabilities` OpenCL filesystem probe  
  - Parts of `TtsVoiceCatalog.availableVoices(Context)`  
- **Whole-module ~12% line** is expected: Activities, Services, LiteRT native, VHAL binders, audio, vision are not unit-covered. Instrumented jacoco is **not wired** in this project — only unit jacoco ships.  
- Claiming 100% of the Android module would be dishonest; core pure logic was maximized aggressively instead.

---

## Fixes applied overnight

| File | Why |
|------|-----|
| `AgentOrchestrator.kt` | Confirm **skipGuard**; decline-first via `ConfirmationPolicy.classify`; clear pending on OTHER |
| `ConfirmationPolicy.kt` | Stronger decline / ambiguous “yes…no…”; bare “please” not affirm |
| `DirectToolResolver.kt` | CITY support; amenity inference; soft `what/who`; trailing `?` strip; face/feet airflow; AMENITY default |
| `vehicle_skills_registry.json` | DirectTool enablement (~64 tools); weather/identity/nearby/news keywords; collision cleanup |
| `SystemToolHandler.kt` | Weather uses live city when CITY/here |
| `NavigationToolHandler.kt` | `suggestNearbyPlaces` real Overpass search (no stub ask) |
| `CabinSnapshot.kt` | Extract `normalizeFuelLevelPct` for testable fuel parsing |
| `*GoldenPath*`, `RequirementsGapFill*`, `CoreCoverage*`, `ConfirmationPolicyTest`, ContextGuard instrumented confirm contract | Lock behavior + raise core coverage |
| Exhaustive follow-up / golden tests | Affirm list without bare “please” |

---

## Open failures

**None** in the final unit or instrumented requirements runs.

Residual **product** gaps are intentional gates (confirm/agentic/free-form/vision) listed in the gap table — not red tests.

---

## Honest limits (not full E2E AI)

- Tests assert **text → DirectTool / ContextGuard / FollowUp**, not mic ASR accuracy.  
- Weather opens a **web search** for the city; it does not invent temperatures offline.  
- Nearby attractions need **network** (Overpass); offline returns an honest failure string.  
- No claim that live Gemma produces the exact spoken line for every MD example.  
- No commit/push performed (per overnight instructions).

---

## How to reproduce

```bash
export TMPDIR=/home/tcs/tmp JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_SERIAL=3704105H8094TU

./gradlew :app:testDebugUnitTest \
  --tests 'com.tcs.vehicleassistant.requirements.*' \
  --tests 'com.tcs.vehicleassistant.core.*' \
  --no-daemon --rerun-tasks

./gradlew :app:jacocoDebugUnitTestReport --no-daemon

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-build-cache
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb install -r --user 10 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w --user 10 \
  -e package com.tcs.vehicleassistant.requirements \
  com.tcs.vehicleassistant.test/androidx.test.runner.AndroidJUnitRunner
```

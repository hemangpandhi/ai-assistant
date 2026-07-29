# Stability verification

How to tell whether a change to this app is safe. Read this before merging anything that touches
the inference pipeline, the audio path, or the vision path.

The short version: `./gradlew clean check assembleRelease && ./scripts/verify_r8_keeps.sh` covers
everything that can be verified off-device, and the on-device checklist below covers what cannot.
Both matter — roughly 95% of this codebase is Android, hardware and UI code that no JVM test can
reach, so a green build is necessary but not sufficient.

---

## 1. Off-device verification

Run this locally before pushing; CI (`.github/workflows/android.yml`) runs the same sequence on
every pull request.

| Command | What it protects |
|---|---|
| `./gradlew :app:assembleDebug` | Compilation, resource and manifest merging |
| `./gradlew :app:testDebugUnitTest` | 176 unit tests over the pure assistant logic |
| `./gradlew :app:jacocoDebugUnitTestReport` | Coverage report at `app/build/reports/jacoco/` |
| `./gradlew :app:lintDebug` | Android Lint, blocking, against `app/lint-baseline.xml` |
| `./gradlew :app:assembleRelease` | R8 shrinking, resource shrinking, release signing |
| `./scripts/verify_r8_keeps.sh` | That JNI- and reflection-reached classes survived R8 |
| `./gradlew :app:assembleDebugAndroidTest` | That the instrumented suite still compiles |

Two of these deserve explanation.

**Lint is blocking.** Pre-existing findings are recorded in `app/lint-baseline.xml`; new ones fail
the build. If you add a finding, fix it rather than regenerating the baseline. Regenerate the
baseline only when you have deliberately removed findings, by deleting the file and running
`./gradlew :app:lintDebug` twice (the first run recreates it and aborts by design).

**The R8 keep check is not optional.** R8 cannot see calls that arrive from JNI, from Koin's
type-based lookup, from the manifest, or from `handler_key` strings in
`vehicle_skills_registry.json`. A missing keep rule produces an APK that installs and launches
cleanly, then throws `ClassNotFoundException` the first time the native stack resolves a class by
name. `scripts/verify_r8_keeps.sh` reads `mapping.txt` and asserts each such class survived
unrenamed. If you add a class that is only reached reflectively, add it to both
`app/proguard-rules.pro` and that script.

### What the unit tests actually cover

| Suite | Subject |
|---|---|
| `GpuBackendResolverTest` | The GPU → CPU fallback policy, including that every chain terminates on CPU |
| `KernelCacheManagerTest` | Kernel-cache invalidation on model or backend change; that the cache is outside evictable and backup-eligible storage |
| `WakeWordActionTest` | The stop/pause/restart action aliases, including the legacy names |
| `WakeWordMatchingTest` | Wake-word gating and Vosk transcript extraction |
| `ToolRetrieverTest` | BM25 ranking, stop-word handling, and that an unmatched query returns nothing |
| `ToolManagerTest` | Retrieval, alias resolution and prompt assembly against the real registry (Robolectric) |
| `ToolCallParserTest` | Tool-call extraction and tag stripping, including partial mid-stream tags |
| `MemoryManagerHistoryTest` | Retention cap, sliding window, and concurrent read/write safety |
| `CloudRequestBuilderTest` | That a cloud request always ends with the user's question |
| `StreamTextHandlingTest` | Display normalization and the runaway-generation cut-off |
| `ConfigCoherenceTest` | Relationships between timeouts and thresholds that only break in combination |
| `ConfigurationValidationTest` | Structural validity of `vehicle_skills_registry.json` |
| `FollowUpRouterTest`, `MemoryManagerTest`, `VisionGestureMoodTest`, `DemoSettingsPresetsTest` | Follow-up routing, affirmative detection, mood thresholds, demo presets |

Line coverage is around 5%. That number is low for an honest reason: the tests cover the decision
logic, and almost everything else in this app is a `Service`, an `Activity`, a MediaPipe callback or
a VHAL binder call. Treat the coverage report as a way to confirm that *newly extracted logic* is
tested, not as a quality gate on the whole module. The productive way to raise it is to keep pulling
decisions out of Android callbacks into pure functions, the way `CabinCameraManager.classifyMood`
and `AgentOrchestrator.normalizeForDisplay` were.

### Adding a test for new logic

Put pure logic in `core/` or a `companion object` and test it with plain JUnit. Reach for
Robolectric (`@RunWith(RobolectricTestRunner::class)`) only when you genuinely need a `Context`, and
pair it with `@Config(application = Application::class)` — the real `VehicleApplication` starts Koin
and kicks off model initialization in `onCreate`, which will fail the second test in the class with
`KoinAppAlreadyStartedException`.

---

## 2. On-device verification

The unit suite cannot observe the native engine, the microphone, the camera or the VHAL. Run this
checklist on the target hardware after any change to the inference, audio or vision paths, and after
any change to `proguard-rules.pro`.

Install the release build, not the debug build. The debug variant re-enables cleartext traffic and
registers the `adb` automation receivers; the release variant is what ships, and R8 only runs there.

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c && adb logcat | tee /tmp/assistant.log
```

### GPU backend and kernel cache

1. **Backend selection.** Search the log for `DeviceCapabilities` and `LLMManager`. On a Pixel
   Tablet you should see an OpenCL driver path and the engine initializing on `GPU`. On hardware
   without a driver you should see the downgrade to `CPU` and a working assistant — a dead assistant
   means the fallback chain regressed.
2. **Cold-start cost.** Time the first initialization after `pm clear`. This pays the full OpenCL
   kernel compile.
3. **Warm-start cost.** Force-stop and relaunch. This must be markedly faster; if it is not, the
   kernel cache is not being written or is being invalidated on every start. Check
   `KernelCacheManager` log lines and confirm files exist under
   `/data/data/com.tcs.vehicleassistant/no_backup/litertlm_kernel_cache/`.
4. **Cache invalidation.** Switch models in the app and confirm the cache is discarded rather than
   reused. Reusing kernels across models crashes inside the GPU delegate rather than failing
   cleanly.
5. **Memory pressure.** Trigger `onTrimMemory` (`adb shell am send-trim-memory <pid> RUNNING_CRITICAL`)
   while a generation is in flight. The engine must not be unloaded mid-inference; `LLMManager`
   refuses when `activeInferences > 0`. A native crash here means that guard regressed.

### Microphone handoff

This is the path that was broken before: the wake-word process held `AudioRecord` open, so the
recognizer could never acquire the microphone.

1. Say the wake word. The overlay should appear and start listening.
2. Speak a command. It must be transcribed. Silence here is the handoff failing.
3. Confirm in the log that the wake-word service released the microphone, then re-acquired it after
   the session ended.
4. Repeat five or six times in a row. The failure mode was intermittent, so a single pass proves
   little.
5. Cancel a session mid-listen and confirm the wake word still works afterwards.

### Resource lifecycle

Exercise the app for a few minutes, then look for leaks:

```bash
adb shell dumpsys meminfo com.tcs.vehicleassistant   # watch native heap across sessions
adb shell dumpsys media.audio_flinger | grep -i "input\|track"  # no orphaned records
adb shell dumpsys media.camera | grep -i "client"               # camera released after vision stops
```

Open and close the assistant overlay ten times and confirm the native heap returns to roughly its
starting size. Then stop the vision service and confirm the camera client disappears.

### Vehicle control safety

1. Run a few tool-invoking commands and confirm the VHAL write is acknowledged.
2. Confirm safety constraints still block: attempt a constrained action while its precondition is
   violated and check the refusal message comes from the registry's `error_msg`.
3. Confirm the automation receivers are gone from the release build:
   ```bash
   adb shell am broadcast -a com.tcs.vehicleassistant.DIAGNOSTICS_DUMP
   ```
   Nothing should happen. If the diagnostics sweep runs, the `BuildConfig.DEBUG` gate regressed and
   any installed app can actuate vehicle controls.

### Cloud fallback

With a key configured, force a cloud query and confirm an API error surfaces as an error state
rather than being spoken aloud as an assistant reply.

---

## 3. Known risks not addressed here

These are real and deliberately out of scope for this pass; they need a decision rather than a
refactor.

- **Face profiles are no longer shared across Android OS users.** `FaceProfileManager` used to mirror
  embeddings to `/sdcard/FaceProfiles.json` so every OS user could read them. The app holds no
  storage permission and scoped storage blocks that write, so the mirror never worked on the target
  image; embeddings now live only in app-private device-protected storage. If cross-user face
  matching is a product requirement, it needs a platform-signed `ContentProvider`, not a file in
  world-readable storage.
- **Reflection into `@hide` APIs.** `AAOSUserSwitchManager` reaches internal platform APIs by
  reflection. This works on the target image and can break on any OS update; it is baselined in
  Lint, not fixed.
- **The release APK is around 400 MB**, almost entirely models in `assets/`: the Vosk wake-word
  model (214 MB), the Whisper STT model (141 MB) and the Piper TTS voice (81 MB). Shipping these as
  on-demand downloads rather than bundled assets is the single largest size win available, and it
  would also let the app pick model sizes per device.
- **R8 has not been validated at runtime.** The keep rules are verified against `mapping.txt`, which
  proves the classes exist under their original names, not that every reflective lookup succeeds.
  The first on-device run of a release build after any change to `proguard-rules.pro` is a real test
  and should be treated as one.
- **`app/platform.jks` is committed with the password in `build.gradle.kts`.** That is fine for a
  platform-signed development image and unacceptable for a production release; wire the signing
  config to environment variables or a keystore service before shipping.
- **Instrumented tests are compile-verified only.** `app/src/androidTest` builds in CI but nothing
  runs it. Running it needs an AAOS emulator image with the `android.car` service, which is a
  meaningful piece of CI infrastructure to stand up.

---

## 4. Triage

| Symptom | Look at |
|---|---|
| Assistant never becomes ready | `LLMManager` init logs; the backend fallback chain; `AssistantConfig.Session.LLM_READY_TIMEOUT_MS` |
| Wake word works, speech is never transcribed | Microphone handoff; `AssistantConfig.WakeWordAction` aliases; `AudioRecord` acquisition retries |
| First response is very slow every launch | Kernel cache not persisting; check `KernelCacheManager` and the `no_backup` directory |
| Model calls the wrong tool | `ToolRetriever` ranking and the `keywords`/`aliases` in `vehicle_skills_registry.json` |
| Tool tags read aloud | `ToolCallParser.stripToolTags`; check the partial-tag cases |
| Release build crashes where debug does not | R8. Run `scripts/verify_r8_keeps.sh`, then deobfuscate the stack with `mapping.txt` |
| API errors spoken as assistant replies | A cloud manager routing to `onMessage` instead of `onError` |

# AGENTS.md

## Cursor Cloud specific instructions

This repo is a single Android Automotive OS (AAOS) app — `VehicleEdgeAssistant`
(package `com.tcs.vehicleassistant`), built with Gradle (Kotlin DSL). Modules:
`:app` (application shell / manifest), `:agent-core` (origin/master-owned agent),
`:assistant-ext` (UI/UX / TTFR extensions), `:assistant-ui` (Compose face/overlay),
and `:assistant-api` (host-neutral LLM/tool ports).
There is no backend/server to run; the "product" is an installable APK. See
`README.md` and `docs/ARCHITECTURE.md` for product/architecture details and
`app/build.gradle.kts` for the module config.

### Hard rule: do not modify `origin/master`-owned classes

This branch is based on `origin/master`. Master-owned Kotlin lives in **`:agent-core`** (byte-identical to
`origin/master` `app/src/main/java`). UI/UX / TTFR work goes in
**`:assistant-ext`** (and `:assistant-ui` / `:assistant-api`):

- **Do not edit** `:agent-core` `.kt` files unless **extremely required**.
- Prefer new types in `:assistant-ext`, ports in `:assistant-api`, and
  manifest / Koin wiring in `:app` / `:assistant-ext`.
- After any accidental touch, restore from `origin/master` into `:agent-core`.
- Enforced by `MasterOwnedTreeContractTest` (path-mapped allowlist).

### Toolchain (non-obvious)
- **Use JDK 17, not the VM default JDK 21.** AGP 8.7.3 / Gradle 8.9 require Java 17;
  building with 21 fails. `JAVA_HOME` and `ANDROID_HOME` are exported in `~/.bashrc`
  (JDK 17 at `/usr/lib/jvm/java-17-openjdk-amd64`, SDK at `~/android-sdk`).
- The Android SDK (`platforms;android-34`, `build-tools;34.0.0`, `platform-tools`)
  lives at `~/android-sdk`. `local.properties` (gitignored) sets `sdk.dir` to it;
  the update script recreates it if missing.
- No NDK is required for `assembleDebug` despite the `arm64-v8a` `abiFilters` (there
  is no C/C++ source; native `.so` files come prebuilt from dependencies).

### Build / test / lint (from repo root)
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Unit tests: `./gradlew testDebugUnitTest` (exercises core JSON tool-routing /
  HVAC command logic in `app/src/test/...`).
- Lint: `./gradlew lintDebug`. **This currently fails** with ~58 pre-existing
  code-level errors (e.g. `MissingPermission`); this is an existing code issue, not
  an environment problem. `abortOnError` is not disabled. Full report at
  `app/build/intermediates/lint_intermediate_text_report/debug/lint-results-debug.txt`.

### Running the app (important caveat)
- This is a **privileged system-level assistant** for AAOS (uses `android.car`
  VHAL, `VoiceInteractionService`, root/`priv-app` install). It **cannot be run in
  this headless cloud VM**: it targets `arm64-v8a` only and needs an AAOS
  device/emulator with root + privileged install (see `README.md` "Executing on
  Custom Hardware"). The realistic dev-loop here is **build + unit tests**.
- Instrumented tests (`./gradlew connectedAndroidTest`) require a connected AAOS
  device/emulator and will not run here.

### Misc
- `build_error.txt` in the repo root is a **stale** artifact from an old failure
  (missing `android.useAndroidX`); `gradle.properties` already sets it, so ignore it.
- LLM model files (`*.task`, `*.litertlm`, `*.bin`) are gitignored, multi-GB, and
  are pushed to a device via ADB (`download_models.sh` / `setup_model.sh`) — never
  commit them.

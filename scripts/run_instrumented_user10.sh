#!/usr/bin/env bash
# Run instrumented requirement suite on AAOS secondary user 10.
# Usage:
#   ./scripts/run_instrumented_user10.sh              # representative subset (fast)
#   ./scripts/run_instrumented_user10.sh all          # full androidTest overnight suite
#   ./scripts/run_instrumented_user10.sh play         # playMusic artist cases only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export TMPDIR="${TMPDIR:-/home/tcs/tmp}"
mkdir -p "$TMPDIR"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

DEVICE="${ANDROID_SERIAL:-3704105H8094TU}"
USER_ID="${ANDROID_USER:-10}"
PACKAGE="com.tcs.vehicleassistant"
TEST_PACKAGE="${PACKAGE}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

MODE="${1:-subset}"

case "$MODE" in
  all)
    # Full androidTest except LLM UI soak (AssistantEndToEndUITest needs live model ~minutes/query).
    CLASS_FILTER=""
    EXTRA_INSTR=(-e notClass com.tcs.vehicleassistant.AssistantEndToEndUITest)
    ;;
  play)
    CLASS_FILTER="com.tcs.vehicleassistant.requirements.PlayMusicArtistInstrumentedTest"
    EXTRA_INSTR=()
    ;;
  report)
    # Prefer dedicated report runner (writes JSON/MD under docs/reports/).
    exec "$ROOT/scripts/run_tablet_usecase_report.sh"
    ;;
  overnight)
    # Same as all — intended for long unattended runs.
    CLASS_FILTER=""
    EXTRA_INSTR=(-e notClass com.tcs.vehicleassistant.AssistantEndToEndUITest)
    ;;
  subset|*)
    # Representative overnight-safe subset: playMusic bug + use-case matrix + catalogue sanity
    CLASS_FILTER="com.tcs.vehicleassistant.requirements.PlayMusicArtistInstrumentedTest,com.tcs.vehicleassistant.requirements.UseCaseScenarioInstrumentedTest,com.tcs.vehicleassistant.requirements.ToolManagerPathInstrumentedTest,com.tcs.vehicleassistant.requirements.DirectToolCatalogueSanityInstrumentedTest,com.tcs.vehicleassistant.requirements.ExhaustiveRegistryInstrumentedTest"
    EXTRA_INSTR=()
    ;;
esac

echo "==> Device=$DEVICE user=$USER_ID mode=$MODE"
adb -s "$DEVICE" wait-for-device
adb -s "$DEVICE" shell am get-current-user || true

echo "==> Building APKs (Java 17, no build cache)"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-build-cache

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "==> Installing to user $USER_ID"
adb -s "$DEVICE" install -r --user "$USER_ID" "$APK"
adb -s "$DEVICE" install -r --user "$USER_ID" "$TEST_APK"

INSTR_ARGS=(-w --user "$USER_ID")
INSTR_ARGS+=("${EXTRA_INSTR[@]}")
if [[ -n "$CLASS_FILTER" ]]; then
  INSTR_ARGS+=(-e class "$CLASS_FILTER")
fi

echo "==> Running instrumented tests"
adb -s "$DEVICE" shell am instrument "${INSTR_ARGS[@]}" "${TEST_PACKAGE}/${RUNNER}"

echo ""
echo "Unit tests (host): TMPDIR=$TMPDIR JAVA_HOME=$JAVA_HOME ./gradlew :app:testDebugUnitTest --no-build-cache"
echo "Full overnight:    $0 overnight"
echo "Parameterized matrix only: adb shell am instrument -w --user $USER_ID -e class com.tcs.vehicleassistant.requirements.DirectToolRegistryInstrumentedTest ${TEST_PACKAGE}/${RUNNER}"

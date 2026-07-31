#!/usr/bin/env bash
# Build, install, and run the standalone tablet use-case report suite, then pull
# JSON + Markdown reports for next-step triage.
#
# Usage:
#   ANDROID_SERIAL=<serial> ./scripts/run_tablet_usecase_report.sh
#   ANDROID_USER=10 ./scripts/run_tablet_usecase_report.sh
#
# Outputs (host):
#   docs/reports/tablet_usecase_report.json
#   docs/reports/tablet_usecase_report.md
#   docs/reports/tablet_usecase_instrumentation.log
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export TMPDIR="${TMPDIR:-/tmp}"
mkdir -p "$TMPDIR"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export PATH="$JAVA_HOME/bin:${ANDROID_HOME}/platform-tools:$PATH"

DEVICE="${ANDROID_SERIAL:-}"
USER_ID="${ANDROID_USER:-10}"
PACKAGE="com.tcs.vehicleassistant"
TEST_PACKAGE="${PACKAGE}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CLASS="com.tcs.vehicleassistant.requirements.StandaloneTabletUseCaseReportTest"
OUT_DIR="$ROOT/docs/reports"
DEVICE_JSON="/data/local/tmp/vehicleassistant_usecase_report.json"
DEVICE_MD="/data/local/tmp/vehicleassistant_usecase_report.md"

if [[ -z "$DEVICE" ]]; then
  DEVICE="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "$DEVICE" ]]; then
  echo "ERROR: no ANDROID_SERIAL and no adb device connected." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/tablet_usecase_instrumentation.log"

echo "==> Device=$DEVICE user=$USER_ID"
adb -s "$DEVICE" wait-for-device

echo "==> Building debug + androidTest APKs"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-build-cache

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "==> Installing on user $USER_ID"
adb -s "$DEVICE" install -r --user "$USER_ID" "$APK"
adb -s "$DEVICE" install -r --user "$USER_ID" "$TEST_APK"

# Clear previous report artifacts
adb -s "$DEVICE" shell rm -f "$DEVICE_JSON" "$DEVICE_MD" || true

echo "==> Running StandaloneTabletUseCaseReportTest"
set +e
adb -s "$DEVICE" shell am instrument -w --user "$USER_ID" \
  -e class "$CLASS" \
  -e deviceSerial "$DEVICE" \
  -e userId "$USER_ID" \
  "${TEST_PACKAGE}/${RUNNER}" | tee "$LOG"
INSTR_RC=${PIPESTATUS[0]}
set -e

echo "==> Pulling reports"
pulled=0
if adb -s "$DEVICE" shell "test -f $DEVICE_JSON"; then
  adb -s "$DEVICE" pull "$DEVICE_JSON" "$OUT_DIR/tablet_usecase_report.json"
  pulled=1
fi
if adb -s "$DEVICE" shell "test -f $DEVICE_MD"; then
  adb -s "$DEVICE" pull "$DEVICE_MD" "$OUT_DIR/tablet_usecase_report.md"
  pulled=1
fi

if [[ "$pulled" -eq 0 ]]; then
  echo "WARN: device report files missing; synthesizing from instrumentation log." >&2
  {
    echo "# Standalone tablet use-case report"
    echo
    echo "_Report files were not found on device. Instrumentation log:_ \`$LOG\`"
    echo
    echo '```'
    tail -n 80 "$LOG"
    echo '```'
  } > "$OUT_DIR/tablet_usecase_report.md"
fi

echo ""
echo "Reports:"
echo "  $OUT_DIR/tablet_usecase_report.md"
echo "  $OUT_DIR/tablet_usecase_report.json"
echo "  $LOG"

if [[ -f "$OUT_DIR/tablet_usecase_report.json" ]]; then
  python3 - <<'PY' "$OUT_DIR/tablet_usecase_report.json" || true
import json,sys
p=sys.argv[1]
d=json.load(open(p))
print(f"Summary: {d.get('passed')}/{d.get('total')} passed, {d.get('failed')} failed")
buckets=d.get("nextStepBuckets") or {}
for k,v in buckets.items():
    if v:
        print(f"  next-step {k}: {len(v)} case(s) → {', '.join(v[:8])}{'...' if len(v)>8 else ''}")
if d.get("failed",0)==0:
    print("Next: run human mic rows in docs/use_cases/DRIVER_SEAT_TABLET_SUITE.md (wake/barge-in/8-turn).")
PY
fi

exit "$INSTR_RC"

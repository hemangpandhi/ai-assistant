#!/usr/bin/env bash
# Edge release verification gate (offline + compile). Exit non-zero on any failure.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export PATH="$JAVA_HOME/bin:${ANDROID_HOME:+$ANDROID_HOME/platform-tools:}$PATH"

if [[ ! -f local.properties ]]; then
  {
    [[ -n "${ANDROID_HOME:-}" ]] && echo "sdk.dir=${ANDROID_HOME}"
    echo "GEMINI_API_KEY="
  } > local.properties
fi

echo "=== Gate 1a: assembleDebug ==="
./gradlew :app:assembleDebug --stacktrace

echo "=== Gate 1b: unit tests ==="
./gradlew :app:testDebugUnitTest --stacktrace

echo "=== Gate 1c: jacoco ==="
./gradlew :app:jacocoDebugUnitTestReport --stacktrace

echo "=== Gate 1d: lintDebug ==="
./gradlew :app:lintDebug --stacktrace

echo "=== Gate 1e: assembleRelease + R8 keeps ==="
./gradlew :app:assembleRelease --stacktrace
./scripts/verify_r8_keeps.sh

echo "=== Gate 1f: assembleDebugAndroidTest ==="
./gradlew :app:assembleDebugAndroidTest --stacktrace

echo "=== OFFLINE GATE PASS ==="

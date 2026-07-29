#!/usr/bin/env bash
#
# Verifies that the classes the app only reaches through JNI, reflection or the manifest survived
# R8 with their original names.
#
# R8 cannot see any of these call sites, so a missing keep rule does not fail the build -- it
# produces an APK that installs, launches, and then throws ClassNotFoundException the first time
# the native inference stack or the DI container looks a class up by name. Checking mapping.txt is
# the only way to catch that without a device.
#
# Run after ./gradlew :app:assembleRelease.

set -euo pipefail

MAPPING="app/build/outputs/mapping/release/mapping.txt"

if [[ ! -f "$MAPPING" ]]; then
  echo "error: $MAPPING not found. Run './gradlew :app:assembleRelease' first." >&2
  exit 1
fi

# Manifest components, Koin-injected types, and the JSON-keyed tool handler registry.
REQUIRED_CLASSES=(
  com.tcs.vehicleassistant.VehicleApplication
  com.tcs.vehicleassistant.LocalLLMActivity
  com.tcs.vehicleassistant.AssistantVoiceInteractionService
  com.tcs.vehicleassistant.AssistantSessionService
  com.tcs.vehicleassistant.WakeWordService
  com.tcs.vehicleassistant.service.VehicleAgentService
  com.tcs.vehicleassistant.vision.CockpitVisionService
  com.tcs.vehicleassistant.ToolManager
  com.tcs.vehicleassistant.repository.AgentOrchestrator
  com.tcs.vehicleassistant.handlers.ToolHandlerRegistry
  com.tcs.vehicleassistant.llm.EdgeLLMProvider
  com.tcs.vehicleassistant.llm.CloudLLMProvider
  com.tcs.vehicleassistant.hardware.AndroidAudioManager
)

# Package prefixes whose classes are resolved from native code by JNI signature.
REQUIRED_PREFIXES=(
  com.google.ai.edge.litertlm.
  org.vosk.
)

failures=0

for class in "${REQUIRED_CLASSES[@]}"; do
  if grep -qxF "$class -> $class:" "$MAPPING"; then
    printf 'ok       %s\n' "$class"
  else
    printf 'MISSING  %s (absent from the APK or renamed by R8)\n' "$class" >&2
    failures=$((failures + 1))
  fi
done

for prefix in "${REQUIRED_PREFIXES[@]}"; do
  count=$(grep -cE "^${prefix//./\\.}[A-Za-z0-9_\$]+ -> ${prefix//./\\.}" "$MAPPING" || true)
  if [[ "$count" -gt 0 ]]; then
    printf 'ok       %s* (%s classes kept unrenamed)\n' "$prefix" "$count"
  else
    printf 'MISSING  %s* (no classes kept unrenamed; JNI lookups will fail)\n' "$prefix" >&2
    failures=$((failures + 1))
  fi
done

if [[ "$failures" -gt 0 ]]; then
  echo >&2
  echo "error: $failures required class(es) did not survive R8. Add keep rules to app/proguard-rules.pro." >&2
  exit 1
fi

echo
echo "All reflectively-reached classes survived R8."

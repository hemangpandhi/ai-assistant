#!/usr/bin/env bash
# ==============================================================================
# mock_tokyo_location.sh — Set system mock GPS via ADB (Pixel Tablet / AAOS)
# ==============================================================================
# Pixel Tablet has no GPS. Official workaround:
# https://developer.android.com/training/cars/testing/aaos-on-pixel#location
#
# Usage:
#   ./mock_tokyo_location.sh              # Tokyo (default)
#   ./mock_tokyo_location.sh 35.57 139.37 # custom lat lon (Sagamihara)
#   ./mock_tokyo_location.sh --clear      # disable mock providers
#   ./mock_tokyo_location.sh -s SERIAL    # target specific device
# ==============================================================================

set -euo pipefail

# Central Tokyo (lat, lon) — ADB --location uses lat,lon (not lon,lat)
LAT="${LAT:-35.6895}"
LON="${LON:-139.6917}"
DEVICE_ARGS=()
CLEAR=false
# "passive" cannot be a test provider on AAOS / Pixel Tablet
PROVIDERS=(gps fused network)

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s)
      DEVICE_ARGS=(-s "$2")
      shift 2
      ;;
    --clear)
      CLEAR=true
      shift
      ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *)
      if [[ "$1" =~ ^-?[0-9]+\.?[0-9]*$ ]]; then
        LAT="$1"
        LON="${2:?Provide longitude after latitude}"
        shift 2
      else
        echo "Unknown arg: $1" >&2
        exit 1
      fi
      ;;
  esac
done

run_adb() {
  adb "${DEVICE_ARGS[@]}" "$@"
}

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 1
fi

DEVICES=$(run_adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$DEVICES" ]]; then
  echo "No ADB device connected. Plug in the Pixel Tablet and enable USB debugging." >&2
  exit 1
fi

echo "Enabling location + mock permission for shell (uid 2000)..."
run_adb shell cmd location set-location-enabled true || true
run_adb shell appops set 2000 android:mock_location allow
# Also allow our app if installed (harmless if missing)
run_adb shell appops set com.tcs.vehicleassistant android:mock_location allow 2>/dev/null || true

if [[ "$CLEAR" == true ]]; then
  echo "Disabling mock providers..."
  for p in "${PROVIDERS[@]}"; do
    run_adb shell cmd location providers set-test-provider-enabled "$p" false 2>/dev/null || true
  done
  echo "Done."
  exit 0
fi

echo "Setting mock location to lat=$LAT lon=$LON (Tokyo by default)..."
for p in "${PROVIDERS[@]}"; do
  run_adb shell cmd location providers add-test-provider "$p" 2>/dev/null || true
  run_adb shell cmd location providers set-test-provider-enabled "$p" true || {
    echo "  skip enable: $p" >&2
    continue
  }
  run_adb shell cmd location providers set-test-provider-location "$p" --location "${LAT},${LON}" || {
    echo "  skip set-location: $p" >&2
    continue
  }
  echo "  ok: $p"
done

echo ""
echo "Last reported locations:"
run_adb shell dumpsys location | grep -i "last location" | head -20 || true
echo ""
echo "Mock location set. Re-run after reboot if location goes stale."

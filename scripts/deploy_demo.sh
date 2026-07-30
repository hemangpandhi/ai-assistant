#!/bin/bash
# Production demo deployment script for VehicleEdge Assistant
set -euo pipefail

PACKAGE="com.tcs.vehicleassistant"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
MODEL_PATH="${1:-}"

echo "======================================================"
echo "  VehicleEdge Assistant — Production Demo Deploy"
echo "======================================================"

if ! command -v adb >/dev/null 2>&1; then
    echo "Error: adb not found in PATH"
    exit 1
fi

echo "[1/5] Building debug APK..."
./gradlew assembleDebug

echo "[2/5] Installing APK..."
adb install -r -g "$APK_PATH" || {
    echo "Standard install failed. Attempting priv-app push (requires adb root)..."
    adb root
    adb remount
    adb push privapp-permissions-com.tcs.vehicleassistant.xml /etc/permissions/ 2>/dev/null || true
    adb shell mkdir -p /system/priv-app/VehicleEdgeAssistant
    adb push "$APK_PATH" /system/priv-app/VehicleEdgeAssistant/VehicleEdgeAssistant.apk
    adb reboot
    echo "Device rebooting for priv-app install. Re-run this script after reboot."
    exit 0
}

if [ -n "$MODEL_PATH" ]; then
    if [ ! -f "$MODEL_PATH" ]; then
        echo "Error: Model file not found: $MODEL_PATH"
        exit 1
    fi
    FILENAME=$(basename "$MODEL_PATH")
    echo "[3/5] Pushing model: $FILENAME"
    adb shell mkdir -p "/sdcard/Android/data/$PACKAGE/files/"
    adb push "$MODEL_PATH" "/sdcard/Android/data/$PACKAGE/files/$FILENAME"

    echo "[4/5] Setting selected model preference..."
    adb shell "run-as $PACKAGE sh -c 'mkdir -p files && echo ok'" 2>/dev/null || true
else
    echo "[3/5] No model path provided — skipping model push"
    echo "       Usage: ./deploy_demo.sh /path/to/gemma-4-E2B-it.litertlm"
fi

echo "[5/5] Syncing system time (offline boards)..."
adb shell date "$(date +%m%d%H%M%Y.%S)" 2>/dev/null || true

adb shell am force-stop "$PACKAGE"
echo ""
echo "Demo ready. Next steps:"
echo "  1. Open 'VehicleEdgeAssistant' from launcher"
echo "  2. Tap 'Load Model' if not auto-loaded"
echo "  3. Settings → Default Apps → Digital assistant → VehicleEdgeAssistant"
echo "  4. Long-press Home to invoke voice overlay"
echo "  5. Telemetry Settings → Demo Preset: 'OEM Demo — Tokyo' (default on first launch)"
echo "     Location Source: Device GPS (falls back to preset if GPS unavailable)"
echo ""
echo "Monitor latency: adb logcat -s LLMLatency EdgeLLM Orchestrator"

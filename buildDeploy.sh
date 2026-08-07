#!/usr/bin/env bash
# ==============================================================================
# buildDeploy.sh - Fast Build and Deployment Script for Automotive AI Assistant
# ==============================================================================

set -e

# Color output formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

PACKAGE_NAME="com.tcs.vehicleassistant"
LAUNCH_ACTIVITY="com.tcs.vehicleassistant/.LocalLLMActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

# Default options
DO_CLEAN=false
DO_LAUNCH=true
DO_LOGCAT=false
DEVICE_FLAG=""
GRADLE_FLAGS="--parallel --build-cache"

# Usage help
show_help() {
    echo -e "${CYAN}Usage:${NC} ./buildDeploy.sh [OPTIONS]"
    echo ""
    echo "Fast build and deploy script for $PACKAGE_NAME"
    echo ""
    echo -e "${YELLOW}Options:${NC}"
    echo "  -c, --clean         Clean project before building"
    echo "  -s <device_serial> Target specific ADB device"
    echo "  --no-launch         Build and install without launching the app"
    echo "  -l, --logcat        Start logcat output after app launch"
    echo "  --offline           Run Gradle in offline mode"
    echo "  -h, --help          Show this help message"
    echo ""
    exit 0
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--clean)
            DO_CLEAN=true
            shift
            ;;
        -s)
            DEVICE_FLAG="-s $2"
            shift 2
            ;;
        --no-launch)
            DO_LAUNCH=false
            shift
            ;;
        -l|--logcat)
            DO_LOGCAT=true
            shift
            ;;
        --offline)
            GRADLE_FLAGS="$GRADLE_FLAGS --offline"
            shift
            ;;
        -h|--help)
            show_help
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            show_help
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}   Automotive AI Assistant Fast Build & Deploy      ${NC}"
echo -e "${BLUE}====================================================${NC}"

# Check for ADB availability
if ! command -v adb &> /dev/null; then
    echo -e "${RED}Error: 'adb' tool is not found in your PATH.${NC}"
    exit 1
fi

# Check connected ADB devices
DEVICES_COUNT=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)

if [ "$DEVICES_COUNT" -eq 0 ]; then
    echo -e "${RED}Error: No ADB device or emulator connected.${NC}"
    echo -e "${YELLOW}Please connect a device via USB/Wi-Fi or start an emulator and try again.${NC}"
    exit 1
elif [ "$DEVICES_COUNT" -gt 1 ] && [ -z "$DEVICE_FLAG" ]; then
    echo -e "${YELLOW}Multiple devices detected:${NC}"
    adb devices
    FIRST_DEVICE=$(adb devices | grep -v "List of devices" | grep -v "^$" | head -n 1 | awk '{print $1}')
    DEVICE_FLAG="-s $FIRST_DEVICE"
    echo -e "${CYAN}Defaulting to device: $FIRST_DEVICE${NC}"
fi

START_TIME=$(date +%s)

# Clean step if requested
if [ "$DO_CLEAN" = true ]; then
    echo -e "${YELLOW}Cleaning project...${NC}"
    ./gradlew clean
fi

# Fast Build
echo -e "${CYAN}Building Debug APK with Gradle optimizations...${NC}"
./gradlew assembleDebug $GRADLE_FLAGS

BUILD_TIME=$(($(date +%s) - START_TIME))
echo -e "${GREEN}Build completed in ${BUILD_TIME}s!${NC}"

# Verify APK creation
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}Error: APK not found at $APK_PATH${NC}"
    exit 1
fi

# Deploy
echo -e "${CYAN}Installing APK to device ($DEVICE_FLAG)...${NC}"
INSTALL_START=$(date +%s)
adb $DEVICE_FLAG install -r -d -g "$APK_PATH"
INSTALL_TIME=$(($(date +%s) - INSTALL_START))
echo -e "${GREEN}Installation complete in ${INSTALL_TIME}s!${NC}"

# Launch
if [ "$DO_LAUNCH" = true ]; then
    echo -e "${CYAN}Launching $LAUNCH_ACTIVITY ...${NC}"
    adb $DEVICE_FLAG shell am start -n "$LAUNCH_ACTIVITY"
fi

TOTAL_TIME=$(($(date +%s) - START_TIME))
echo -e "${GREEN}====================================================${NC}"
echo -e "${GREEN}   Success! Total time: ${TOTAL_TIME}s                     ${NC}"
echo -e "${GREEN}====================================================${NC}"

# Stream logcat if requested
if [ "$DO_LOGCAT" = true ]; then
    echo -e "${CYAN}Clearing logcat buffer and streaming logs... (Ctrl+C to stop)${NC}"
    adb $DEVICE_FLAG logcat -c
    adb $DEVICE_FLAG logcat --pid=$(adb $DEVICE_FLAG shell pidof -s $PACKAGE_NAME)
fi

#!/bin/bash

echo "===================================================="
echo "   Android Automotive LLM - Local Push Script  "
echo "===================================================="
echo ""
echo "This script pushes the local 4K Qwen/Gemma model directly to the app's external"
echo "storage, bypassing Android 14 SELinux restrictions and skipping the download."
echo ""
echo "Please specify the local path to your downloaded .litertlm or .bin file:"
read -p "> " MODEL_PATH

if [ ! -f "$MODEL_PATH" ]; then
    echo "Error: File not found at $MODEL_PATH"
    exit 1
fi
FILENAME=$(basename "$MODEL_PATH")

# Ensure the app is installed before we do this
adb shell pm path com.tcs.vehicleassistant > /dev/null
if [ $? -ne 0 ]; then
    echo "Error: The app com.tcs.vehicleassistant is not installed."
    echo "Please install the APK first before pushing the model."
    exit 1
fi

echo "Pushing $FILENAME to the Android device via ADB..."
# Push directly to the app's external files directory
# No chown, SELinux, or restorecon hacks needed here!
adb shell mkdir -p /sdcard/Android/data/com.tcs.vehicleassistant/files/
adb push "$MODEL_PATH" "/sdcard/Android/data/com.tcs.vehicleassistant/files/$FILENAME"

echo "Done! You can now launch the app and it will immediately load the model!"

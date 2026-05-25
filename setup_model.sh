#!/bin/bash

echo "===================================================="
echo "   Android Automotive LLM - Local Push Script  "
echo "===================================================="
echo ""
echo "This script pushes the local Gemma 4 model directly to the app's internal"
echo "storage, bypassing Android 14 SELinux restrictions and skipping the download."
echo ""
echo "Please specify the local path to your downloaded .litertlm file:"
read -p "> " MODEL_PATH

if [ ! -f "$MODEL_PATH" ]; then
    echo "Error: File not found at $MODEL_PATH"
    exit 1
fi

echo "Pushing $MODEL_PATH to the Android device via ADB..."
adb root
adb shell mkdir -p /data/data/com.example.gemininano/files/
# Push to tmp first because adb push directly to data/data can sometimes fail
adb push "$MODEL_PATH" /data/local/tmp/gemma-4-E2B-it.litertlm
echo "Moving to secure app storage..."
adb shell mv /data/local/tmp/gemma-4-E2B-it.litertlm /data/data/com.example.gemininano/files/gemma-4-E2B-it.litertlm
adb shell chmod 666 /data/data/com.example.gemininano/files/gemma-4-E2B-it.litertlm

echo "Done! You can now launch the app and it will immediately load the model!"

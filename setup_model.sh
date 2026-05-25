#!/bin/bash

echo "===================================================="
echo "   Android Automotive LLM - MediaPipe Setup Script  "
echo "===================================================="
echo ""
echo "This script assumes you have downloaded the Gemma-2B-IT model."
echo "You can download it from Kaggle (requires login & consent):"
echo "URL: https://www.kaggle.com/models/google/gemma/tfLite/gemma-2b-it-gpu-int4"
echo ""
echo "Please specify the local path to your downloaded .bin file:"
read -p "> " MODEL_PATH

if [ ! -f "$MODEL_PATH" ]; then
    echo "Error: File not found at $MODEL_PATH"
    exit 1
fi

echo "Pushing $MODEL_PATH to the Android device via ADB..."
adb root
adb push "$MODEL_PATH" /data/local/tmp/gemma-2b-it-gpu-int4.bin
adb shell chmod 666 /data/local/tmp/gemma-2b-it-gpu-int4.bin

echo "Done! The model is now ready for the Android Automotive Sample App."

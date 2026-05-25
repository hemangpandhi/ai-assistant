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
FILENAME=$(basename "$MODEL_PATH")

adb root
# Wait for root to restart
sleep 1

# Ensure the app is installed before we do this
adb shell pm path com.example.gemininano > /dev/null
if [ $? -ne 0 ]; then
    echo "Error: The app com.example.gemininano is not installed."
    echo "Please install the APK first before pushing the model."
    exit 1
fi

echo "Pushing $FILENAME to the Android device via ADB..."
# Push to tmp first
adb push "$MODEL_PATH" "/data/local/tmp/$FILENAME"

echo "Configuring permissions and secure app storage..."
# Get the app's UID/GID
APP_UID=$(adb shell stat -c %U /data/data/com.example.gemininano)

# Create the files directory and fix ownership
adb shell mkdir -p /data/data/com.example.gemininano/files/
adb shell chown $APP_UID:$APP_UID /data/data/com.example.gemininano/files/

# Move the file and fix ownership/contexts
adb shell mv "/data/local/tmp/$FILENAME" "/data/data/com.example.gemininano/files/$FILENAME"
adb shell chown $APP_UID:$APP_UID "/data/data/com.example.gemininano/files/$FILENAME"
adb shell chmod 666 "/data/data/com.example.gemininano/files/$FILENAME"
adb shell restorecon -R /data/data/com.example.gemininano/

echo "Done! You can now launch the app and it will immediately load the model!"

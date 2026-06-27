# Manual LLM Deployment Guide (Android Automotive)

When pushing massive `.litertlm` models to an Android Automotive head unit (like the Qualcomm SA8255), standard `adb push` commands often fail due to Android 14's strict FUSE (Filesystem in Userspace) cache and internal UID permissions.

If you ever need to manually push a model in the future, follow these exact 4 steps to guarantee it works.

## Prerequisites
Ensure your Android device is connected via ADB and that you know the exact filename of your downloaded model (e.g., `gemma-4-E2B-it.litertlm`).

> [!WARNING]
> Do not push `.safetensors` or `.bin` files. The app strictly requires `.litertlm` or `.task` bundles.

---

## Step 1: Push to the Temporary Directory
Because pushing a 2GB+ file directly to the app's secure internal storage will result in a `Permission Denied` error, you must first push it to the device's temporary folder.

```bash
# Replace 'gemma-4-E2B-it.litertlm' with your model's filename
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
```

## Step 2: Copy to Internal Storage (Root Bypass)
To bypass the FUSE filesystem cache (which causes the "Model not found" error), we use a root command to copy the file from the temporary directory directly into the app's internal, non-cached data directory.

```bash
adb shell "su root cp /data/local/tmp/gemma-4-E2B-it.litertlm /data/user/10/com.tcs.vehicleassistant/files/"
```

## Step 3: Fix Linux UID Permissions
When an Android app is installed, the OS assigns it a unique User ID (UID). On your system, the app's current live UID is `1010002`. Because we copied the file using `root`, the app is currently locked out of reading it. We must transfer ownership of the file to the app.

```bash
adb shell "su root chown 1010002:1010002 /data/user/10/com.tcs.vehicleassistant/files/gemma-4-E2B-it.litertlm"
```
*(Note: If you ever uninstall and reinstall the app, Android will assign it a new UID. You can find the new UID by running `adb shell "ps -A | grep vehicleassistant"`, which will output something like `u10_a2`. The number `2` corresponds to UID `1010002`.)*

## Step 4: Delete Old Models and Force Restart
To ensure the app auto-discovers your new model, delete any old models in that folder, then force-stop the application to clear its memory.

```bash
# (Optional) Delete old models to prevent conflicts
adb shell "su root rm -f /data/user/10/com.tcs.vehicleassistant/files/Qwen2.5.litertlm"

# Force restart the app
adb shell "am force-stop com.tcs.vehicleassistant && am start -n com.tcs.vehicleassistant/.LocalLLMActivity"
```

## Summary (All-in-One Command)
You can string all of these commands together into a single terminal execution:
```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/ && adb shell "su root cp /data/local/tmp/gemma-4-E2B-it.litertlm /data/user/10/com.tcs.vehicleassistant/files/ && su root chown 1010002:1010002 /data/user/10/com.tcs.vehicleassistant/files/gemma-4-E2B-it.litertlm && am force-stop com.tcs.vehicleassistant && am start -n com.tcs.vehicleassistant/.LocalLLMActivity"
```

# Fixing SELinux `PERMISSION_DENIED` for Local AI Models

When pushing model files directly to an Android device (especially secondary users like user 10 on Automotive), you may encounter a `PERMISSION_DENIED` error when the app's native code tries to load the model.

Even if the file has read/write permissions (`-rw-rw-rw-`), Android's SELinux might block access due to mismatched Multi-Category Security (MCS) labels.

## How to Diagnose

1. **Check the file's SELinux context:**
   ```bash
   adb shell "ls -Z /data/user/10/com.tcs.vehicleassistant/files/Llama-3.2-3B-Instruct-LiteRT.litertlm"
   ```
   *Output example:* `u:object_r:app_data_file:s0:c2,c256,c522,c768`

2. **Check the app's process context:**
   First, find the app's PID:
   ```bash
   adb shell pidof com.tcs.vehicleassistant
   ```
   Then check the process SELinux context using the PID:
   ```bash
   adb shell ps -Z | grep <PID>
   ```
   *Output example:* `u:r:platform_app:s0:c522,c768`

   *Notice that the file has extra labels (`c2,c256`) that the app process does not have. This mismatch causes the denial.*

3. **Check the directory's expected context:**
   ```bash
   adb root
   adb shell "ls -Zd /data/user/10/com.tcs.vehicleassistant/files/"
   ```
   *Output example:* `u:object_r:app_data_file:s0:c522,c768`

## How to Fix

You can fix this by aligning the file's SELinux context with the directory's context (which matches the app process). 

Run the following commands to change the SELinux context of the files using `chcon`:

```bash
# Gain root access
adb root

# Apply the correct SELinux context recursively to the files directory
adb shell "chcon -R u:object_r:app_data_file:s0:c522,c768 /data/user/10/com.tcs.vehicleassistant/files/"
```

*(Note: Replace the context string `u:object_r:app_data_file:s0:c522,c768` with the actual expected context from step 3 if it differs for your device/user).*

After running this, the app should be able to load the model without permission errors.

# Vehicle Hardware Abstraction Layer (VHAL) Diagnostics Guide

This guide explains how to identify which VHAL properties and Area IDs your specific Android Automotive hardware (such as the SA8255 platform) supports, and how to update the Vehicle Edge AI Assistant to use them.

## Step 1: Run On-Device Diagnostics
The Vehicle Edge AI Assistant includes a built-in diagnostic tool to scan the VHAL.

1. Launch the **Vehicle Assistant** app (the main full-screen chat interface, not the voice overlay).
2. Tap the text input box at the bottom.
3. Type exactly: `/diagnostics` and press Send.
4. The AI will instantly generate and display a large Markdown table. This table will list every hardware property (e.g., `HVAC_AC_ON`, `CURRENT_GEAR`, `PERF_VEHICLE_SPEED`), its supported data type, and whether your specific VHAL supports it. It will also show its current value or any vendor error codes.

## Step 2: Identify the Correct IDs
Review the diagnostics output or your Android Logcat for errors.
For example, if you see an error like:
`failed to set value for propID: 354419973, areaID: 5`
This indicates the vendor HAL advertised support for Area ID `5` (Row 1 Left + Right), but when the app attempted to write to it, the hardware rejected it. 
Using the `/diagnostics` tool output, you can identify if your board requires a different Area ID, such as `1` (Driver only) or `0` (Global).

## Step 3: Update the Configuration File
Once you have identified the correct `property_id` and `area_id` for your hardware, you must update the app's tool registry.

1. In your project source code, open the file: `app/src/main/assets/vehicle_skills_registry.json`.
2. Find the JSON configuration block for the failing tool. For example:
   ```json
   {
     "prompt_string": "<TOOL>turnOnAC()</TOOL>",
     "handler_type": "GENERIC_VHAL_WRITE",
     "property_id": 354419973,
     "data_type": "BOOLEAN",
     "area_id": 0,
     "value_to_write": "true"
   }
   ```
3. Update the `property_id` or `area_id` values to match your board's requirements. 
   *(Note: If `area_id` is set to `0`, the app automatically queries the HAL for the first supported area ID. If the HAL provides a faulty ID, you can bypass it by hardcoding the correct integer here, such as `1`)*.
4. Save the `vehicle_skills_registry.json` file.
5. Recompile and install the app onto your device to apply the new VHAL mappings.

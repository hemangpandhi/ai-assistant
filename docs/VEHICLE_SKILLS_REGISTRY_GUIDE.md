# Vehicle Skills Registry Guide

This guide comprehensively details how to configure `vehicle_skills_registry.json`. This file acts as the central nervous system of the Assistant, seamlessly bridging the local AI model to the Android Automotive Vehicle Hardware Abstraction Layer (VHAL) without requiring you to write complex code for every new feature.

### 🌟 Absolute Beginner's Summary
If you have zero prior knowledge of Android or coding, don't worry! This file is written in **JSON**, which is just a simple way to store data using text. 

Think of this file as the **"Brain Dictionary"** for the AI Assistant. It tells the AI two basic things:
1. **What it can "See" (`properties`)**: These are read-only sensors. By adding a sensor here, you let the AI know what the car is doing (like if the battery is low, or if a door is open).
2. **What it can "Do" (`tools`)**: These are physical actions. By adding a tool here, you give the AI the ability to push a button in the car (like rolling down a window or turning on the AC).

To add a new feature to the car, you don't need to write any complicated Kotlin code. You just copy-paste a block of text into this JSON file and change the numbers to match your specific car!

---

## 1. Finding Your Property IDs
Before adding a new feature, you must know its VHAL Property ID. 
1. Connect to your vehicle/emulator via ADB: `adb shell dumpsys car_service --hal`
2. Search the output for your feature to find its Hexadecimal ID (e.g., `Property: 0x16200b02 (DOOR_LOCK)`).
3. Convert that Hex ID to a **Decimal integer** (e.g., `0x16200b02` → `371198722`).

You can also look up IDs in the official AOSP [VehicleProperty.aidl](https://cs.android.com/android/platform/superproject/+/master:hardware/interfaces/automotive/vehicle/aidl/android/hardware/automotive/vehicle/VehicleProperty.aidl).

---

## 2. How to Fix or Update an Existing Property/Tool

If an existing tool (like `<TOOL>openTrunk()</TOOL>`) isn't working on your specific car/emulator, it means the `"property_id"` mapped in the JSON doesn't match your car's hardware.

**To fix an existing tool or property:**
1. Run `adb shell dumpsys car_service --hal` to find the correct Hex ID for your car's specific feature.
2. Convert that Hex ID to a Decimal integer.
3. Open `vehicle_skills_registry.json`.
4. Find the broken tool or property block (e.g., search for `"prompt_string": "<TOOL>openTrunk()</TOOL>"`).
5. Simply overwrite the existing `"property_id"` field with your new decimal integer!

---

## 3. Adding Contextual Properties (Read-Only Sensors)

If you want the AI to know about a specific vehicle state (like tire pressure, battery level, or outside temperature) so it can answer user questions, add a block to the `"properties"` array.

**Example: Adding an EV Battery Sensor**
```json
{
  "name": "EV_BATTERY_LEVEL",
  "id": 291504905,
  "type": "FLOAT"
}
```

**Advanced: Adding Custom Instructions**
If the sensor data is complex, you can explicitly instruct the LLM on how to interpret it using the `instruction` field:
```json
{
  "name": "ADAS_OSE_DOOR_ALERT",
  "id": 639631617,
  "type": "BOOLEAN",
  "instruction": "If the user asks about the door status, you MUST read the ADAS_OSE_DOOR_ALERT property and warn them if it is true."
}
```

---

## 4. Adding Actionable Tools (Generic VHAL Write)

If you want the AI to physically control a feature (like turning on ambient lights or opening a window), you can use a `GENERIC_VHAL_WRITE` tool. This requires **zero Kotlin code**; the system handles the reflection and hardware writes automatically.

Add a block to the `"tools"` array:

```json
{
  "prompt_string": "<TOOL>setAmbientLight()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 356518835,
  "data_type": "INT",
  "area_id": 0,
  "value_to_write": "5",
  "success_message": "I've changed the ambient lighting color.",
  "keywords": ["ambient", "light", "color", "mood", "lighting"]
}
```

### Breakdown of Tool Fields:
- **`prompt_string`**: The exact XML string the AI will output when it decides to use this tool (e.g., `<TOOL>setAmbientLight()</TOOL>`). This must exactly match the name of your feature so the parser can catch it.
- **`handler_type`**: Must be exactly `"GENERIC_VHAL_WRITE"` for automatic hardware execution. If you need complex logic, set this to `"CUSTOM_KOTLIN"` instead.
- **`property_id`**: The Decimal VHAL ID you extracted in Step 1.
- **`data_type`**: The type of data to write (`INT`, `FLOAT`, `BOOLEAN`, or `STRING`). 
  - *How to find it:* Look at the `VehiclePropertyType` in your `dumpsys` output or the AIDL file.
- **`area_id`**: The physical zone of the car (e.g., driver seat vs passenger seat).
  - *How to find it:* Look at your `dumpsys` output for the property. It will list the supported `AreaId`s.
  - If the property affects the whole car (like Trunk, AC Power, or Ambient Light), the Area ID is almost always `0`.
  - If it is zone-specific (like Windows or Doors), it will be a bitmask or specific ID (e.g., `1` for Front Left, `2` for Front Right). If you want the tool to affect a specific window, use that exact `AreaId`. If you put `0` for a zoned property, the `VehicleManager` will usually fallback and apply it to the first available area.
- **`value_to_write`**: The specific value to push to the VHAL when this tool is triggered.
  - *How to find it:* 
    - For `BOOLEAN` properties (like Defroster or AC Power), this will always be `"true"` or `"false"`.
    - For `INT` properties (like Window Position or Fan Speed), look at the `dumpsys` output for `min` and `max` limits. For example, if Window Position accepts 0-100, you would write `"100"` to fully open it. If it's an enum (like Ambient Light colors), look up the specific integer mapping in your OEM's HAL documentation.
- **`success_message`**: The human-readable confirmation the Assistant will speak out loud when the command succeeds.
- **`keywords`**: An array of relevant words. 
  - *How to fill this:* Think of synonyms a user might say. The Semantic Search Engine (RAG) uses these to intelligently route the user's voice prompt to this specific tool without sending the whole list to the LLM. For an ambient light tool, you would use `["ambient", "light", "color", "mood", "lighting"]`.

---

## 5. Advanced Tool Configuration (Safety & Guardrails)

For dangerous operations (like opening trunks or unlocking doors while driving), you can inject native safety guardrails directly into the JSON.

### A. Requiring User Confirmation
If an action is high-risk, force the AI to ask for permission before executing it:
```json
{
  "prompt_string": "<TOOL>unlockDoors()</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 371198722,
  "data_type": "BOOLEAN",
  "area_id": 0,
  "value_to_write": "false",
  "requires_confirmation": true,
  "confirmation_message": "Security Warning: Are you sure you want to unlock the vehicle doors?"
}
```

### B. Hardware Safety Constraints
You can prevent a tool from executing if a different sensor is in a dangerous state. For example, prevent the windows from opening if the vehicle is moving too fast:
```json
{
  "prompt_string": "<TOOL>setWindowPosition(PCT)</TOOL>",
  "handler_type": "GENERIC_VHAL_WRITE",
  "property_id": 322964416,
  "constraints": [
    {
      "property_id": 291504647, // PERF_VEHICLE_SPEED
      "operator": "<",
      "value": 70, // Max speed 70 km/h
      "error_msg": "Safety Warning: Speed is too high to safely open the windows."
    }
  ]
}
```

---

## 6. Adding Complex Commands (Custom Kotlin)

If a command requires complex logic (like parsing arguments from the AI, e.g., `<TOOL>setTemperature(72)</TOOL>`), a `GENERIC_VHAL_WRITE` is not enough. You must use `CUSTOM_KOTLIN`.

1. **Define it in the JSON:**
```json
{
  "prompt_string": "<TOOL>setTemperature(VAL)</TOOL>",
  "handler_key": "setTemperature",
  "handler_type": "CUSTOM_KOTLIN",
  "keywords": ["temperature", "hot", "cold", "warm", "cool", "ac", "heater", "climate"]
}
```

2. **Map it in Kotlin (`ToolManager.kt`):**
```kotlin
private suspend fun executeTool(toolCall: String): String {
    // ...
    return when (toolDef.handlerKey) {
        "setTemperature" -> {
            val value = extractArgument(toolCall)
            // Your custom Kotlin logic to set the temp based on 'value'
            "I've set the temperature to $value degrees."
        }
        // ...
    }
}
```

---

## 7. Agentic & Multi-Turn Features (New)

With the migration to a fully Agentic Architecture, the registry now supports recursive tool execution and multi-turn interaction configuration natively.

### A. The Agentic Loop (`requires_agentic_loop`)
You only need to add this flag for **Information Retrieval** tools. 

**Ask yourself:** *"Does the LLM need to read the tool's output to formulate its final answer to the user?"*
*   **Yes?** Add `"requires_agentic_loop": true`. 
    *   *Example (`searchNearby`)*: The user asks for restaurants. The tool reaches out to an API and returns a raw JSON block of coordinates and names. The LLM *must* run a second time to read that JSON and turn it into a conversational sentence: *"I found three sushi places nearby..."*
    *   *Example (`queryMemory`)*: The user asks "When is my wife's birthday?". The tool queries the database and returns `[Memory: Wife birthday is June 12]`. The LLM *must* loop again to say: *"Your wife's birthday is on June 12th."*
*   **No?** Omit the flag (or set it to `false`).
    *   *Example (`turnOnAC`, `openTrunk`, `setWindowPosition`)*: The user says "Open the window". The tool triggers the hardware and returns `"I've opened the windows."` There is nothing left for the LLM to "think" about. The UI speaks the success message immediately, saving an expensive second AI processing cycle!

```json
{
  "prompt_string": "<TOOL>queryMemory(QUERY)</TOOL>",
  "handler_type": "CUSTOM_KOTLIN",
  "requires_agentic_loop": true
}
```

### B. Forcing Multi-Turn Follow-Ups
To ensure the LLM remains conversational during complex workflows, you can embed explicit multi-turn instructions directly into the `prompt_string`!
```json
{
  "prompt_string": "If you use this tool, you MUST end your final response by asking the user: 'Would you like me to navigate to any of these locations?' \n<TOOL>searchNearby(POI)</TOOL>"
}
```

### C. Offline Fallback Routing (`offline_capable`)
By default, the architecture assumes all tools are offline-capable (`true`). You only need to explicitly set it to `false` when the tool **requires an active internet connection to function**.

**Ask yourself:** *"If the car drives into a tunnel with zero cell service, will this tool crash?"*
*   **Yes?** Set `"offline_capable": false`.
    *   *Example (`search`, `searchNearby`)*: These tools fetch live data from the internet (Overpass API / Google Maps). If the car is offline, the tool will fail. By tagging it `false`, the system knows to gracefully block the tool and tell the user, *"I need an internet connection to look that up."*
*   **No?** Omit the flag (defaults to `true`).
    *   *Example (All Car Controls)*: VHAL properties (AC, doors, windows, trunk) run on the local CAN bus. They always work offline.
    *   *Example (`queryMemory`, `remember`)*: Your memory system saves preferences directly to the local Android database on the vehicle's hard drive. It works perfectly in a tunnel!

```json
{
  "prompt_string": "<TOOL>searchNearby(POI)</TOOL>",
  "handler_type": "CUSTOM_KOTLIN",
  "offline_capable": false
}
```

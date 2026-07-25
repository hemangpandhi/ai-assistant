# Comprehensive LLM Training Examples (All Use Cases)

This document contains a canonical Input/Output example for every single tool defined in the `vehicle_skills_registry.json`. The ML Engineer can use this as a reference for the exact expected behavior of every supported feature.

---

## Tool: `setTemperature`
**Trigger phrase example:** "temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: temperature
```

**Expected LLM Output:**
```text
I've adjusted the cabin temperature. <TOOL>setTemperature(VAL)</TOOL>
```

---

## Tool: `increaseTemperature`
**Trigger phrase example:** "increase temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>increaseTemperature(zone)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: increase temperature
```

**Expected LLM Output:**
```text
I'm warming it up for you! <TOOL>increaseTemperature(zone)</TOOL>
```

---

## Tool: `decreaseTemperature`
**Trigger phrase example:** "decrease temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>decreaseTemperature(zone)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: decrease temperature
```

**Expected LLM Output:**
```text
Cooling it down — you should feel better soon. <TOOL>decreaseTemperature(zone)</TOOL>
```

---

## Tool: `setFanSpeed`
**Trigger phrase example:** "fan"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setFanSpeed(LEVEL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: fan
```

**Expected LLM Output:**
```text
I've adjusted the fan speed. <TOOL>setFanSpeed(LEVEL)</TOOL>
```

---

## Tool: `increaseFanSpeed`
**Trigger phrase example:** "increase fan"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>increaseFanSpeed()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: increase fan
```

**Expected LLM Output:**
```text
Cranking up the fan for you! <TOOL>increaseFanSpeed()</TOOL>
```

---

## Tool: `decreaseFanSpeed`
**Trigger phrase example:** "decrease fan"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>decreaseFanSpeed()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: decrease fan
```

**Expected LLM Output:**
```text
Dialed the fan back a notch. <TOOL>decreaseFanSpeed()</TOOL>
```

---

## Tool: `setAirflowDirection`
**Trigger phrase example:** "airflow"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setAirflowDirection(direction)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: airflow
```

**Expected LLM Output:**
```text
Adjusting the airflow — you should feel it soon. <TOOL>setAirflowDirection(direction)</TOOL>
```

---

## Tool: `turnOnAC`
**Trigger phrase example:** "turn on ac"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnAC()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn on ac
```

**Expected LLM Output:**
```text
AC is on — you should feel that soon. <TOOL>turnOnAC()</TOOL>
```

---

## Tool: `turnOffAC`
**Trigger phrase example:** "turn off ac"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffAC()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off ac
```

**Expected LLM Output:**
```text
I've turned off the AC. <TOOL>turnOffAC()</TOOL>
```

---

## Tool: `turnOnAutoClimate`
**Trigger phrase example:** "auto"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnAutoClimate()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: auto
```

**Expected LLM Output:**
```text
I've turned on automatic climate control. <TOOL>turnOnAutoClimate()</TOOL>
```

---

## Tool: `turnOffAutoClimate`
**Trigger phrase example:** "turn off automatic"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffAutoClimate()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off automatic
```

**Expected LLM Output:**
```text
I've disabled automatic climate control. <TOOL>turnOffAutoClimate()</TOOL>
```

---

## Tool: `turnOnRecirculation`
**Trigger phrase example:** "turn on recirculation"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnRecirculation()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn on recirculation
```

**Expected LLM Output:**
```text
I've turned on air recirculation. <TOOL>turnOnRecirculation()</TOOL>
```

---

## Tool: `turnOffRecirculation`
**Trigger phrase example:** "turn off recirculation"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffRecirculation()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off recirculation
```

**Expected LLM Output:**
```text
I've turned off air recirculation. <TOOL>turnOffRecirculation()</TOOL>
```

---

## Tool: `turnOnHvacPower`
**Trigger phrase example:** "turn on hvac"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnHvacPower()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn on hvac
```

**Expected LLM Output:**
```text
I've turned on the climate control system. <TOOL>turnOnHvacPower()</TOOL>
```

---

## Tool: `turnOffHvacPower`
**Trigger phrase example:** "turn off hvac"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffHvacPower()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off hvac
```

**Expected LLM Output:**
```text
I've turned off the climate control system. <TOOL>turnOffHvacPower()</TOOL>
```

---

## Tool: `turnOnDefroster`
**Trigger phrase example:** "turn on front defroster"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnDefroster()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn on front defroster
```

**Expected LLM Output:**
```text
Defroster's on — your view should clear up. <TOOL>turnOnDefroster()</TOOL>
```

---

## Tool: `turnOffDefroster`
**Trigger phrase example:** "turn off front defroster"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffDefroster()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off front defroster
```

**Expected LLM Output:**
```text
Deactivating front defroster. <TOOL>turnOffDefroster()</TOOL>
```

---

## Tool: `turnOnRearDefroster`
**Trigger phrase example:** "turn on rear defroster"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnRearDefroster()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn on rear defroster
```

**Expected LLM Output:**
```text
Activating rear defroster. <TOOL>turnOnRearDefroster()</TOOL>
```

---

## Tool: `turnOffRearDefroster`
**Trigger phrase example:** "turn off rear defroster"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffRearDefroster()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off rear defroster
```

**Expected LLM Output:**
```text
Deactivating rear defroster. <TOOL>turnOffRearDefroster()</TOOL>
```

---

## Tool: `openWindowsSlightly`
**Trigger phrase example:** "open windows slightly"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>openWindowsSlightly()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: open windows slightly
```

**Expected LLM Output:**
```text
I've opened the windows slightly. <TOOL>openWindowsSlightly()</TOOL>
```

---

## Tool: `closeAllWindows`
**Trigger phrase example:** "close windows"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>closeAllWindows()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: close windows
```

**Expected LLM Output:**
```text
I've closed all the windows. <TOOL>closeAllWindows()</TOOL>
```

---

## Tool: `turnOnCabinLight`
**Trigger phrase example:** "turn on cabin lights"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOnCabinLight()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn on cabin lights
```

**Expected LLM Output:**
```text
I've turned on the cabin lights. <TOOL>turnOnCabinLight()</TOOL>
```

---

## Tool: `turnOffCabinLight`
**Trigger phrase example:** "turn off cabin lights"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>turnOffCabinLight()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: turn off cabin lights
```

**Expected LLM Output:**
```text
I've turned off the cabin lights. <TOOL>turnOffCabinLight()</TOOL>
```

---

## Tool: `setSeatHeater`
**Trigger phrase example:** "warm seat"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setSeatHeater(LEVEL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: warm seat
```

**Expected LLM Output:**
```text
Seat heater's on — hope that warms you up! <TOOL>setSeatHeater(LEVEL)</TOOL>
```

---

## Tool: `setSeatMassager`
**Trigger phrase example:** "back massage"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setSeatMassager(LEVEL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: back massage
```

**Expected LLM Output:**
```text
I've turned on the seat massager for you. <TOOL>setSeatMassager(LEVEL)</TOOL>
```

---

## Tool: `checkAllWindowsClosed`
**Trigger phrase example:** "are all windows closed"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>checkAllWindowsClosed()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: are all windows closed
```

**Expected LLM Output:**
```text
I've checked the status of all windows. <TOOL>checkAllWindowsClosed()</TOOL>
```

---

## Tool: `movePassengerSeatForward`
**Trigger phrase example:** "move passenger seat forward"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>movePassengerSeatForward()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: move passenger seat forward
```

**Expected LLM Output:**
```text
I've moved the passenger seat forward. <TOOL>movePassengerSeatForward()</TOOL>
```

---

## Tool: `setEnergeticCabinLighting`
**Trigger phrase example:** "ambient"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setEnergeticCabinLighting()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: ambient
```

**Expected LLM Output:**
```text
Okay, I've set the cabin lighting to an energetic mood. <TOOL>setEnergeticCabinLighting()</TOOL>
```

---

## Tool: `enableFreshAirIntake`
**Trigger phrase example:** "fresh air"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>enableFreshAirIntake()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: fresh air
```

**Expected LLM Output:**
```text
I've opened the windows slightly for fresh air. <TOOL>enableFreshAirIntake()</TOOL>
```

---

## Tool: `protectFromPollutedAir`
**Trigger phrase example:** "too much pollution"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>protectFromPollutedAir()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: too much pollution
```

**Expected LLM Output:**
```text
I've closed the windows and enabled recirculation. <TOOL>protectFromPollutedAir()</TOOL>
```

---

## Tool: `defogWindshield`
**Trigger phrase example:** "visibility"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>defogWindshield()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: visibility
```

**Expected LLM Output:**
```text
Clearing your windshield — safety first. <TOOL>defogWindshield()</TOOL>
```

---

## Tool: `improveRoadVisibility`
**Trigger phrase example:** "too dark"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>improveRoadVisibility()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: too dark
```

**Expected LLM Output:**
```text
I've turned on the defrosters and optimized your airflow for maximum visibility. <TOOL>improveRoadVisibility()</TOOL>
```

---

## Tool: `handleDrowsyDriving`
**Trigger phrase example:** "feel sleepy"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>handleDrowsyDriving()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: feel sleepy
```

**Expected LLM Output:**
```text
I've adjusted the cabin to help keep you awake. <TOOL>handleDrowsyDriving()</TOOL>
```

---

## Tool: `enhanceNiceEvening`
**Trigger phrase example:** "nice evening"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>enhanceNiceEvening()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: nice evening
```

**Expected LLM Output:**
```text
I've adjusted the cabin for a pleasant evening drive. <TOOL>enhanceNiceEvening()</TOOL>
```

---

## Tool: `suggestOptimizedChargingRate`
**Trigger phrase example:** "optimize charging"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>suggestOptimizedChargingRate()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: optimize charging
```

**Expected LLM Output:**
```text
I've analyzed the battery and suggested an optimized charging rate. <TOOL>suggestOptimizedChargingRate()</TOOL>
```

---

## Tool: `sendUpcomingEventReminder`
**Trigger phrase example:** "event reminder"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>sendUpcomingEventReminder()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: event reminder
```

**Expected LLM Output:**
```text
I've checked your upcoming events and prepared a reminder. <TOOL>sendUpcomingEventReminder()</TOOL>
```

---

## Tool: `explainChildSeatInstallation`
**Trigger phrase example:** "child seat"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>explainChildSeatInstallation()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: child seat
```

**Expected LLM Output:**
```text
I'll walk you through how to install the child seat safely. <TOOL>explainChildSeatInstallation()</TOOL>
```

---

## Tool: `suggestUmbrellaIfRainy`
**Trigger phrase example:** "umbrella"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>suggestUmbrellaIfRainy()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: umbrella
```

**Expected LLM Output:**
```text
I've checked the weather and will remind you to carry an umbrella if rain is expected. <TOOL>suggestUmbrellaIfRainy()</TOOL>
```

---

## Tool: `getNewsHighlights`
**Trigger phrase example:** "today's news"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>getNewsHighlights()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: today's news
```

**Expected LLM Output:**
```text
Here are today's news highlights. <TOOL>getNewsHighlights()</TOOL>
```

---

## Tool: `handleDriverFatigue`
**Trigger phrase example:** "fatigue"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>handleDriverFatigue(ALERT_LEVEL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: fatigue
```

**Expected LLM Output:**
```text
I've assessed your fatigue level and prepared recommendations. <TOOL>handleDriverFatigue(ALERT_LEVEL)</TOOL>
```

---

## Tool: `alertDriverDistraction`
**Trigger phrase example:** "distracted"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>alertDriverDistraction()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: distracted
```

**Expected LLM Output:**
```text
I'll monitor for distraction and alert you if needed. <TOOL>alertDriverDistraction()</TOOL>
```

---

## Tool: `provideLaneLevelGuidance`
**Trigger phrase example:** "which lane"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>provideLaneLevelGuidance()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: which lane
```

**Expected LLM Output:**
```text
I'll guide you with lane-level navigation. <TOOL>provideLaneLevelGuidance()</TOOL>
```

---

## Tool: `suggestAlternateRoute`
**Trigger phrase example:** "traffic congestion"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>suggestAlternateRoute()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: traffic congestion
```

**Expected LLM Output:**
```text
I've checked traffic and found an alternate route. <TOOL>suggestAlternateRoute()</TOOL>
```

---

## Tool: `adjustBgmForSituation`
**Trigger phrase example:** "background music"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>adjustBgmForSituation()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: background music
```

**Expected LLM Output:**
```text
I've adjusted the background music to match the cabin situation. <TOOL>adjustBgmForSituation()</TOOL>
```

---

## Tool: `checkVehicleSecured`
**Trigger phrase example:** "is everything secured"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>checkVehicleSecured()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: is everything secured
```

**Expected LLM Output:**
```text
I've checked whether the vehicle is secured. <TOOL>checkVehicleSecured()</TOOL>
```

---

## Tool: `prepareForCommute`
**Trigger phrase example:** "get the car ready"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForCommute()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: get the car ready
```

**Expected LLM Output:**
```text
I'm preparing the vehicle for your office commute. <TOOL>prepareForCommute()</TOOL>
```

---

## Tool: `optimizeCabinForLongDrive`
**Trigger phrase example:** "long drive"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>optimizeCabinForLongDrive()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: long drive
```

**Expected LLM Output:**
```text
I've optimized the cabin for a long drive. <TOOL>optimizeCabinForLongDrive()</TOOL>
```

---

## Tool: `prepareForIncomingRain`
**Trigger phrase example:** "wet"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForIncomingRain()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: wet
```

**Expected LLM Output:**
```text
I've prepared the vehicle for the incoming rain. <TOOL>prepareForIncomingRain()</TOOL>
```

---

## Tool: `prepareForParking`
**Trigger phrase example:** "about to park"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForParking()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: about to park
```

**Expected LLM Output:**
```text
I've prepared parking assistance for you. <TOOL>prepareForParking()</TOOL>
```

---

## Tool: `optimizeEnergyForRange`
**Trigger phrase example:** "maximum battery range"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>optimizeEnergyForRange()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: maximum battery range
```

**Expected LLM Output:**
```text
I've optimized the vehicle for maximum range. <TOOL>optimizeEnergyForRange()</TOOL>
```

---

## Tool: `makeTripEnjoyable`
**Trigger phrase example:** "make this trip enjoyable"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>makeTripEnjoyable()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: make this trip enjoyable
```

**Expected LLM Output:**
```text
I've adjusted settings to make this trip more enjoyable. <TOOL>makeTripEnjoyable()</TOOL>
```

---

## Tool: `checkTripReadiness`
**Trigger phrase example:** "vehicle healthy"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>checkTripReadiness()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: vehicle healthy
```

**Expected LLM Output:**
```text
I've checked the vehicle's readiness for your trip. <TOOL>checkTripReadiness()</TOOL>
```

---

## Tool: `prepareForElderlyPassengers`
**Trigger phrase example:** "parents travelling"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForElderlyPassengers()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: parents travelling
```

**Expected LLM Output:**
```text
I've adjusted cabin settings for your elderly passengers. <TOOL>prepareForElderlyPassengers()</TOOL>
```

---

## Tool: `prepareForImportantMeeting`
**Trigger phrase example:** "important client meeting"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForImportantMeeting()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: important client meeting
```

**Expected LLM Output:**
```text
I've prepared the cabin and schedule for your important meeting. <TOOL>prepareForImportantMeeting()</TOOL>
```

---

## Tool: `prepareForArrival`
**Trigger phrase example:** "prepare for arrival"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForArrival()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: prepare for arrival
```

**Expected LLM Output:**
```text
I've prepared the vehicle for your arrival. <TOOL>prepareForArrival()</TOOL>
```

---

## Tool: `handleEmergencyFeeling`
**Trigger phrase example:** "something feels wrong with the car"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>handleEmergencyFeeling()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: something feels wrong with the car
```

**Expected LLM Output:**
```text
I've evaluated the vehicle signals and prepared safety recommendations. <TOOL>handleEmergencyFeeling()</TOOL>
```

---

## Tool: `enableAdaptiveNightMode`
**Trigger phrase example:** "night driving is difficult"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>enableAdaptiveNightMode()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: night driving is difficult
```

**Expected LLM Output:**
```text
Adaptive night driving mode is now enabled. <TOOL>enableAdaptiveNightMode()</TOOL>
```

---

## Tool: `prepareForAirportTrip`
**Trigger phrase example:** "going to the airport"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prepareForAirportTrip()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: going to the airport
```

**Expected LLM Output:**
```text
I've prepared navigation and settings for your airport trip. <TOOL>prepareForAirportTrip()</TOOL>
```

---

## Tool: `explainLowRange`
**Trigger phrase example:** "why is my range low"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>explainLowRange()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: why is my range low
```

**Expected LLM Output:**
```text
I've analyzed possible reasons for the lower range. <TOOL>explainLowRange()</TOOL>
```

---

## Tool: `answerVehicleIdentity`
**Trigger phrase example:** "what model is this"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>answerVehicleIdentity()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: what model is this
```

**Expected LLM Output:**
```text
I've checked the vehicle identity. <TOOL>answerVehicleIdentity()</TOOL>
```

---

## Tool: `playMusic`
**Trigger phrase example:** "listen"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>playMusic(SONG)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: listen
```

**Expected LLM Output:**
```text
Great choice — putting that on for you! <TOOL>playMusic(SONG)</TOOL>
```

---

## Tool: `pauseMusic`
**Trigger phrase example:** "pause music"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>pauseMusic()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: pause music
```

**Expected LLM Output:**
```text
I've paused the music. <TOOL>pauseMusic()</TOOL>
```

---

## Tool: `stopMusic`
**Trigger phrase example:** "stop music"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>stopMusic()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: stop music
```

**Expected LLM Output:**
```text
I've stopped the music. <TOOL>stopMusic()</TOOL>
```

---

## Tool: `nextTrack`
**Trigger phrase example:** "next song"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>nextTrack()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: next song
```

**Expected LLM Output:**
```text
I've skipped to the next track. <TOOL>nextTrack()</TOOL>
```

---

## Tool: `prevTrack`
**Trigger phrase example:** "previous song"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>prevTrack()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: previous song
```

**Expected LLM Output:**
```text
I've gone back to the previous track. <TOOL>prevTrack()</TOOL>
```

---

## Tool: `setVolumeLevel`
**Trigger phrase example:** "increase volume"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setVolumeLevel(VAL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: increase volume
```

**Expected LLM Output:**
```text
I've adjusted the volume. <TOOL>setVolumeLevel(VAL)</TOOL>
```

---

## Tool: `callContact`
**Trigger phrase example:** "dial"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>callContact(NAME)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: dial
```

**Expected LLM Output:**
```text
Opening the dialer for you now. <TOOL>callContact(NAME)</TOOL>
```

---

## Tool: `sendText`
**Trigger phrase example:** "text"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>sendText(NAME,MSG)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: text
```

**Expected LLM Output:**
```text
I've sent your message. <TOOL>sendText(NAME,MSG)</TOOL>
```

---

## Tool: `remember`
**Trigger phrase example:** "remember"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>remember(FACT)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: remember
```

**Expected LLM Output:**
```text
I've saved that information. <TOOL>remember(FACT)</TOOL>
```

---

## Tool: `getWeather`
**Trigger phrase example:** "weather"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>getWeather(CITY)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: weather
```

**Expected LLM Output:**
```text
Done. <TOOL>getWeather(CITY)</TOOL>
```

---

## Tool: `searchNearby`
**Trigger phrase example:** "hungry"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>searchNearby(amenity)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: hungry
```

**Expected LLM Output:**
```text
Done. <TOOL>searchNearby(amenity)</TOOL>
```

---

## Tool: `search`
**Trigger phrase example:** "look up"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>search(search_term)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: look up
```

**Expected LLM Output:**
```text
Done. <TOOL>search(search_term)</TOOL>
```

---

## Tool: `startNavigationTo`
**Trigger phrase example:** "drive to"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>startNavigationTo("PLACE_NAME")</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: drive to
```

**Expected LLM Output:**
```text
Done. <TOOL>startNavigationTo("PLACE_NAME")</TOOL>
```

---

## Tool: `openTrunk`
**Trigger phrase example:** "open"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>openTrunk()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: open
```

**Expected LLM Output:**
```text
I've popped the trunk. <TOOL>openTrunk()</TOOL>
```

---

## Tool: `setWindowPosition`
**Trigger phrase example:** "roll down window"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setWindowPosition(PCT)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: roll down window
```

**Expected LLM Output:**
```text
I've adjusted the windows. <TOOL>setWindowPosition(PCT)</TOOL>
```

---

## Tool: `unlockDoors`
**Trigger phrase example:** "unlock"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>unlockDoors()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: unlock
```

**Expected LLM Output:**
```text
I've unlocked the doors. <TOOL>unlockDoors()</TOOL>
```

---

## Tool: `setDriverTemperature`
**Trigger phrase example:** "driver temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setDriverTemperature(VAL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: driver temperature
```

**Expected LLM Output:**
```text
I've adjusted the driver's side temperature. <TOOL>setDriverTemperature(VAL)</TOOL>
```

---

## Tool: `setPassengerTemperature`
**Trigger phrase example:** "passenger temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setPassengerTemperature(VAL)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: passenger temperature
```

**Expected LLM Output:**
```text
I've adjusted the passenger's side temperature. <TOOL>setPassengerTemperature(VAL)</TOOL>
```

---

## Tool: `bookRestaurant`
**Trigger phrase example:** "bookrestaurant"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>bookRestaurant()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: bookrestaurant
```

**Expected LLM Output:**
```text
Done. <TOOL>bookRestaurant()</TOOL>
```

---

## Tool: `queryMemory`
**Trigger phrase example:** "querymemory"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>queryMemory()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: querymemory
```

**Expected LLM Output:**
```text
Done. <TOOL>queryMemory()</TOOL>
```

---

## Tool: `openApp`
**Trigger phrase example:** "openapp"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>openApp()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: openapp
```

**Expected LLM Output:**
```text
Done. <TOOL>openApp()</TOOL>
```

---

## Tool: `setAllWindowsPosition`
**Trigger phrase example:** "setallwindowsposition"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>setAllWindowsPosition()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: setallwindowsposition
```

**Expected LLM Output:**
```text
Done. <TOOL>setAllWindowsPosition()</TOOL>
```

---

## Tool: `checkVehicleState`
**Trigger phrase example:** "check state"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>checkVehicleState()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: check state
```

**Expected LLM Output:**
```text
Done. <TOOL>checkVehicleState()</TOOL>
```

---

## Tool: `increasePassengerTemperature`
**Trigger phrase example:** "increase passenger temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>increasePassengerTemperature()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: increase passenger temperature
```

**Expected LLM Output:**
```text
I've increased the passenger's temperature. <TOOL>increasePassengerTemperature()</TOOL>
```

---

## Tool: `decreasePassengerTemperature`
**Trigger phrase example:** "decrease passenger temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>decreasePassengerTemperature()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: decrease passenger temperature
```

**Expected LLM Output:**
```text
I've decreased the passenger's temperature. <TOOL>decreasePassengerTemperature()</TOOL>
```

---

## Tool: `increaseDriverTemperature`
**Trigger phrase example:** "increase driver temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>increaseDriverTemperature()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: increase driver temperature
```

**Expected LLM Output:**
```text
I've increased the driver's temperature. <TOOL>increaseDriverTemperature()</TOOL>
```

---

## Tool: `decreaseDriverTemperature`
**Trigger phrase example:** "decrease driver temperature"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>decreaseDriverTemperature()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: decrease driver temperature
```

**Expected LLM Output:**
```text
I've decreased the driver's temperature. <TOOL>decreaseDriverTemperature()</TOOL>
```

---

## Tool: `handleFeelingCold`
**Trigger phrase example:** "feeling cold"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>handleFeelingCold()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: feeling cold
```

**Expected LLM Output:**
```text
Would you like me to turn on the seat heater? <TOOL>handleFeelingCold()</TOOL>
```

---

## Tool: `call`
**Trigger phrase example:** "call number"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>call(NUMBER)</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: call number
```

**Expected LLM Output:**
```text
Dialing the number. <TOOL>call(NUMBER)</TOOL>
```

---

## Tool: `suggestNearbyPlaces`
**Trigger phrase example:** "suggest places"

**Expected LLM Input:**
```text
CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion...
[... 14 Strict Rules Included ...]

=== AVAILABLE TOOLS ===
- <TOOL>suggestNearbyPlaces()</TOOL>
- <TOOL>setTemperature(VAL)</TOOL>
- <TOOL>call(NUMBER)</TOOL>

[System Context: Speed=30mph, Gear=Drive]
User: suggest places
```

**Expected LLM Output:**
```text
Here are some nearby places. <TOOL>suggestNearbyPlaces()</TOOL>
```

---


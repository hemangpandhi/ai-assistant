# Context policies (ContextGuard) — OEM / developer guide

Cabin-aware rules that gate tool actuation **before** any write. Policies are declarative JSON in the skills registry — not Kotlin `if` branches and not XML layouts.

| Role | Location |
|------|----------|
| **Policy definitions (edit here)** | `app/src/main/assets/vehicle_skills_registry.json` → `config.context_policies` |
| **Evaluation engine** | `app/src/main/java/com/tcs/vehicleassistant/core/ContextGuard.kt` |
| **Fact snapshot** | `app/src/main/java/com/tcs/vehicleassistant/core/CabinSnapshot.kt` |
| **Nav destination session** | `app/src/main/java/com/tcs/vehicleassistant/core/NavSessionState.kt` |
| **Load** | `ToolManager.initialize` → `ContextGuard.loadFromConfig(config)` |
| **Runtime wiring** | `AgentOrchestrator` (DirectTool, FollowUp, LLM tool loop, confirm yes/decline) |

Architecture pointer (short): [use_cases/CONTEXT_GUARD.md](use_cases/CONTEXT_GUARD.md).

---

## Table of contents

1. [Why ContextGuard](#1-why-contextguard)
2. [End-to-end flow](#2-end-to-end-flow)
3. [Where policies live](#3-where-policies-live)
4. [When code changes are needed](#4-when-code-changes-are-needed)
5. [Top-level schema](#5-top-level-schema)
6. [Rule fields](#6-rule-fields)
7. [`when` clauses](#7-when-clauses)
8. [Sensor conditions](#8-sensor-conditions)
9. [Available sensors](#9-available-sensors)
10. [Message placeholders](#10-message-placeholders)
11. [Matching and priority](#11-matching-and-priority)
12. [Confirm / block / escalate flow](#12-confirm--block--escalate-flow)
13. [How to add a policy (checklist)](#13-how-to-add-a-policy-checklist)
14. [Worked examples](#14-worked-examples)
15. [Built-in rules (current registry)](#15-built-in-rules-current-registry)
16. [Testing](#16-testing)
17. [Troubleshooting](#17-troubleshooting)

---

## 1. Why ContextGuard

DirectTool and the LLM can both propose tool calls such as `setVolumeLevel(up)` or `openTrunk()`. Without a cabin-aware gate, those writes always run. ContextGuard evaluates the proposed call against a live **CabinSnapshot** (VHAL / AudioManager / MediaSession / LocationManager / nav session) and returns one decision:

| Decision | Effect |
|----------|--------|
| **ALLOW** | Execute the tool immediately |
| **CONFIRM** | Speak the policy message; stash the tool call; wait for user yes / decline; **no write yet** |
| **BLOCK** | Speak the reason; **do not** execute |
| **ESCALATE** | Speak a facts-only advisory; **do not** execute (caller may later use a short LLM path) |

Policies stay in JSON so OEMs can tune thresholds, messages, and coverage without shipping new Kotlin branches for each scenario.

---

## 2. End-to-end flow

```text
User query
  → DirectTool / FollowUp / LLM proposes toolCall  (e.g. setVolumeLevel(up))
  → CabinSnapshotReader.capture()                  // facts only — never LLM
  → ContextGuard.evaluate(toolCall, snapshot)
       ALLOW    → ToolManager.executeToolCall
       CONFIRM  → speak message; pendingConfirmationTool = toolCall; wait FollowUp
       BLOCK    → speak reason; no write
       ESCALATE → speak facts-only advisory; no write
```

Both paths are guarded:

- **DirectTool / FollowUp** — `AgentOrchestrator.completeDirectToolTurn`
- **LLM `<TOOL>` loop** — same `evaluateContextGuard` before execute

If snapshot capture throws, the orchestrator **allows** the tool (fail-open) and logs a warning.

---

## 3. Where policies live

**Only** in the registry JSON:

```text
app/src/main/assets/vehicle_skills_registry.json
  └── config
        └── context_policies
              ├── enabled   (master switch)
              └── rules[]   (policy objects)
```

- **Not** in Kotlin (except the engine + snapshot reader).
- **Not** in XML / layouts / strings resources for rule logic.
- **Not** in skill `phrases` / `handler_key` definitions (those select *which* tool runs; policies gate *whether* it writes).

Rebuild / reinstall the app after editing the registry so assets reload. `ToolManager.initialize` calls `ContextGuard.loadFromConfig` once at startup.

---

## 4. When code changes are needed

| Change | Where |
|--------|--------|
| New / edit / disable / retune a policy using **existing** sensors and `when` flags | **JSON only** (`config.context_policies.rules`) |
| Change message copy or thresholds (85% → 90%, 40 mph → 30, etc.) | **JSON only** |
| Disable all policies temporarily | JSON: `"enabled": false` on `context_policies` |
| Disable one rule without deleting it | JSON: `"enabled": false` on that rule |
| New sensor (e.g. `is_raining`) | Kotlin once: expose in `CabinSnapshot` / `CabinSnapshotReader`, map in `sensor()` (+ optional `interpolate()`), then JSON forever |
| New `when` flag (e.g. `doors_locked`) | Kotlin once: parse in `ContextGuard.loadFromConfig` + check in `evaluate`, then JSON forever |
| New comparison operator | Kotlin once: `sensorsMatch` in `ContextGuard.kt` |
| New tool handler name | Registry skill / handler must exist; policy `applies_to` must match that **handler key** |
| Layout / UI | Not used for policies |

Rule of thumb: if `CabinSnapshot.sensor("…")` already returns a value and `when` already has the clause you need, stay in JSON.

---

## 5. Top-level schema

```json
"config": {
  "context_policies": {
    "enabled": true,
    "rules": [ /* PolicyRule objects */ ]
  }
}
```

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `enabled` | bool | `true` | Master switch. `false` → every evaluation returns **ALLOW** (guard skipped). |
| `rules` | array | `[]` | Policy objects. Loaded, sorted by ascending `priority`, then evaluated in that order. |

If `config` has no `context_policies` object, the guard loads zero rules and leaves `enabled = true` (empty rules ⇒ allow everything).

---

## 6. Rule fields

```json
{
  "id": "volume_already_loud",
  "priority": 10,
  "enabled": true,
  "applies_to": ["setVolumeLevel"],
  "when": {
    "arg_matches": ["up", "increase", "+", "louder", "max"],
    "media_playing": true,
    "nav_active": true,
    "nav_dest_matches_arg": false,
    "nav_dest_differs_arg": false,
    "sensors": [
      { "source": "media_volume_pct", "op": ">=", "value": 85 }
    ]
  },
  "action": "confirm",
  "message": "It's already quite loud ({media_volume_pct}%). Increase anyway?"
}
```

### Root fields

| Field | Required | Default | Meaning |
|-------|----------|---------|---------|
| `id` | **yes** | — | Stable unique name for logs / telemetry (`Policy hit id=…`). Snake_case recommended. |
| `priority` | no | `100` | **Lower number runs first.** Use small numbers for hard safety blocks so they beat softer confirms. |
| `enabled` | no | `true` | Per-rule kill switch without deleting the rule. |
| `applies_to` | **yes** (empty ⇒ never matches) | — | Tool **handler keys** this rule can match. Must equal the name before `(` in the tool call (case-insensitive). Example: tool call `setVolumeLevel(up)` → handler `setVolumeLevel`. |
| `when` | no | match-all conditions | Conditions; every present clause must pass (**AND**). Omit or empty → only `applies_to` (and enabled/priority) matter. |
| `action` | **yes** (unknown → allow) | `"allow"` if missing/unknown | What to do on a full match. |
| `message` | strongly recommended for confirm/block/escalate | `"Please confirm."` | Spoken / shown text. Supports `{placeholders}` from the live snapshot. |

### `action` values

| JSON value | Decision | Behavior |
|------------|----------|----------|
| `allow` | `Decision.Allow` | Execute immediately. Rarely useful as an explicit rule; default when nothing matches is already allow. |
| `confirm` | `Decision.Confirm` | Speak `message`, stash original tool call as `pendingConfirmationTool`, wait for yes / decline. **No write** until confirmed. |
| `block` | `Decision.Block` | Speak `message`, **do not** execute. |
| `adjust` | `Decision.Block` | **Alias of `block`** today (same code path). |
| `escalate` | `Decision.Escalate` | Speak facts-only advisory; **do not** execute. Orchestrator may treat this as a question turn; LLM can still be used later by the caller. |
| anything else | `Decision.Allow` | Parsed as allow. |

---

## 7. `when` clauses

All specified clauses must hold (**logical AND**). Omitted clauses are ignored.

| Clause | JSON type | Meaning |
|--------|-----------|---------|
| `arg_matches` | `string[]` | Tool args (text inside `(...)`, quotes stripped, lowercased) must equal or contain **any** listed token (case-insensitive). Empty / omitted → ignore args. |
| `media_playing` | `bool` | Must equal `CabinSnapshot.mediaPlaying`. Omit to ignore. |
| `nav_active` | `bool` | Must equal whether an active nav destination exists (`navActiveDest` non-blank). Omit to ignore. |
| `nav_dest_matches_arg` | `bool` | When `true`, tool destination args must equal/contain (or be contained by) the active nav destination. Fails if no active dest. |
| `nav_dest_differs_arg` | `bool` | When `true`, there must be an active dest **and** args must **not** match it (reroute case). |
| `sensors` | `object[]` | Numeric comparisons against [CabinSnapshot](#9-available-sensors). **All** must pass. |

### `arg_matches` special tokens

Beyond literal substring / equality checks, two tokens expand:

| Token | Also matches when args… |
|-------|-------------------------|
| `up` | contain `up`, `increase`, `+`, `louder`, **or args are empty** |
| `down` | contain `down`, `decrease`, `-`, or `quieter` |

So a volume-up rule with `"arg_matches": ["up", …]` also matches `setVolumeLevel()` with empty args.

### Nav destination matching

`destMatches(toolArgs, activeDest)` (case-insensitive, quotes stripped):

- either string empty → no match  
- else: equal, or either contains the other  

Used by both `nav_dest_matches_arg` and `nav_dest_differs_arg` (differs = active present and `destMatches` is false).

---

## 8. Sensor conditions

```json
{ "source": "speed_mph", "op": ">=", "value": 40 }
```

| Field | Meaning |
|-------|---------|
| `source` | Sensor id passed to `CabinSnapshot.sensor(name)` (see table below). Case-insensitive. |
| `op` | Comparison operator (default `>=` if omitted in JSON). |
| `value` | Number. Booleans are encoded as `1` / `0` (e.g. `hvac_auto_on` `==` `1` means on). |

### Supported operators

| `op` | Meaning |
|------|---------|
| `>=` | greater or equal |
| `>` | greater |
| `<=` | less or equal |
| `<` | less |
| `==` or `=` | equal |
| `!=` | not equal |
| anything else | condition fails |

### Unknown / unavailable sensors

`CabinSnapshot.sensor(name)` returns `null` when:

- the name is not recognized, or  
- a value is explicitly unknown (`fuel_level_pct < 0`, `window_open_pct < 0`, missing lat/lon)

If **any** sensor in the rule returns `null`, `sensorsMatch` fails → **the rule does not match**. Safe default: no false block/confirm when the platform cannot expose that fact.

---

## 9. Available sensors

Facts from VHAL / AudioManager / MediaSession / LocationManager / `NavSessionState` — never invented by the LLM.

| `source` (canonical) | Meaning | Typical range / notes | Aliases accepted by `sensor()` |
|----------------------|---------|------------------------|--------------------------------|
| `media_volume_pct` | Music stream volume % | 0–100 | `volume_pct`, `volume` |
| `media_playing` | Playback active (MediaSession PLAYING/BUFFERING) | 0 / 1 | `playing` |
| `fan_level` | HVAC fan | 0–`fan_max` (default max 7) | `fan` |
| `fan_max` | Fan max | usually 7 (`DEFAULT_FAN_MAX`) | — |
| `cabin_temp_f` | Cabin setpoint °F | device-dependent | `cabin_temp`, `temp_f` |
| `seat_heater_level` | Seat heater | 0–3 | `seat_heater` |
| `seat_heater_max` | Seat heater max | usually 3 | — |
| `ac_on` | AC | 0 / 1 | — |
| `hvac_power_on` | HVAC power | 0 / 1 | — |
| `hvac_auto_on` | Auto climate | 0 / 1 | — |
| `defrost_on` | Defroster | 0 / 1 | — |
| `speed_mph` | Vehicle speed | ≥ 0 | `speed` |
| `is_parked` | Gear looks like Park (`park` / `p` / starts with `park`) | 0 / 1 | `parked` |
| `fuel_level_pct` | Fuel | 0–100; unknown → rule skip | `fuel_pct`, `fuel` |
| `window_open_pct` | Max window open % | 0–100; unknown → rule skip | — |
| `nav_active` | Has active nav dest | 0 / 1 | — |
| `latitude` | Resolved location | degrees; missing → null | `lat` |
| `longitude` | Resolved location | degrees; missing → null | `lon`, `lng` |

**Not sensors** (use dedicated `when` flags instead): `nav_dest_matches_arg`, `nav_dest_differs_arg`, and the boolean `nav_active` / `media_playing` under `when` (those are separate from the numeric `sensors` array, though `media_playing` / `nav_active` also exist as 0/1 sensor sources).

**Do not invent** sources that are not in the table. Adding e.g. `is_raining` requires a Kotlin change first.

### How values are captured (`CabinSnapshotReader`)

| Fact | Source |
|------|--------|
| Volume % | `AudioManager` STREAM_MUSIC current/max |
| Media playing | `MediaSessionManager` active sessions (SecurityException → treat as not playing) |
| Fan / temp / seat / HVAC / defrost / speed / gear / fuel / windows | `VehicleManager` |
| City / lat / lon | `LocationManager` |
| Nav destination | `NavSessionState.activeDest` (set by navigation tool handler) |

---

## 10. Message placeholders

Use `{name}` in `message`. Replaced by `CabinSnapshot.interpolate` from the **live** snapshot (case-insensitive key match).

| Placeholder | Replaced with |
|-------------|----------------|
| `{media_volume_pct}` / `{volume_pct}` | integer volume % |
| `{fan_level}` | fan level |
| `{fan_max}` | fan max |
| `{cabin_temp_f}` | cabin temp °F |
| `{seat_heater_level}` | seat heater level |
| `{speed_mph}` | speed |
| `{gear}` | gear string |
| `{is_parked}` | `yes` / `no` |
| `{fuel_level_pct}` | integer % or `unknown` |
| `{window_open_pct}` | integer % or `unknown` |
| `{media_playing}` | `yes` / `no` |
| `{hvac_auto_on}` | `on` / `off` |
| `{nav_active_dest}` | active destination or `none` |
| `{city}` | city or `unknown` |
| `{latitude}` / `{longitude}` | coordinate string or `unknown` |

Unknown placeholder names are left unchanged in the string.

---

## 11. Matching and priority

Evaluation algorithm (`ContextGuard.evaluate`):

1. If master `enabled` is false **or** rules list is empty → **ALLOW**.
2. Parse `handler` = text before `(`; `args` = inside `(...)`, trimmed, quotes stripped, lowercased.
3. Walk rules already sorted by **`priority` ascending** (stable load order among equal priorities follows JSON order after sort).
4. For each rule:
   - Skip if `enabled == false`.
   - Skip if `applies_to` does not contain `handler` (case-insensitive).
   - Skip if `arg_matches` non-empty and no token matches (including `up`/`down` specials).
   - Skip if `media_playing` / `nav_active` requirements disagree with snapshot.
   - Skip if `nav_dest_matches_arg` / `nav_dest_differs_arg` fail.
   - Skip if any sensor condition fails or is unknown.
5. **First full match wins** → interpolate `message`, return that `action`.
6. If nothing matches → **ALLOW**.

### Priority example

At volume 100% with “volume up”:

| Rule | Priority | Would match? |
|------|----------|--------------|
| `volume_already_max` | **5** | yes → **BLOCK** (wins) |
| `volume_already_loud` | 10 | also true, but never reached |

Safety blocks should use **lower** priority numbers than soft confirms for the same tools.

### Multiple sensors

All sensor objects in one rule are AND-ed. Example — unlock while moving:

```json
"sensors": [
  { "source": "is_parked", "op": "==", "value": 0 },
  { "source": "speed_mph", "op": ">=", "value": 5 }
]
```

Both must pass.

---

## 12. Confirm / block / escalate flow

### Confirm

1. Rule returns `confirm` → orchestrator speaks interpolated `message` (as a question turn).
2. Stores `pendingConfirmationTool` = original tool call string (e.g. `setVolumeLevel(up)`).
3. While pending is set, DirectTool registry hits and FollowUp direct resolution are skipped so the yes/no path stays clear.
4. **Affirmative** (`MemoryManager.isAffirmative`):  
   exact or short phrases such as `yes`, `yeah`, `yep`, `yup`, `sure`, `ok`, `okay`, `do it`, `go ahead`, `please`, or short “yes …” / “ok …” prefixes (≤ 4 words).  
   Pending is cleared and the stashed tool is executed (DirectTool confirm path re-enters the guarded turn; LLM path can execute the stashed call directly).
5. **Decline**: `no`, `nope`, starts with `no `, or contains `don't` / `do not` / `cancel` / `never mind`.  
   Pending cleared; assistant speaks `Okay, I won't change that.`
6. Other utterances while pending: DirectTool/FollowUp short-circuits stay off; LLM path may treat non-affirmative as abort of the pending action.

### Block

Speak `message`; do not stash; do not execute. Session continues as a normal statement turn.

### Escalate

Speak `message` as a question-style turn; do not execute. Intended for “here are the facts — decide with LLM / user” without writing hardware yet. No built-in registry rule currently uses `escalate`; the action is implemented for OEM use.

### Block vs confirm vs escalate (choose carefully)

| Goal | Action |
|------|--------|
| Hard safety / impossible state (already max, moving + trunk) | `block` |
| Risky but user may insist (loud volume, unlock while moving) | `confirm` |
| Advisory only; defer richer reasoning | `escalate` |

---

## 13. How to add a policy (checklist)

1. Open `app/src/main/assets/vehicle_skills_registry.json`.
2. Locate `config.context_policies.rules`.
3. Confirm `context_policies.enabled` is `true` (or intentionally false for a global off switch).
4. Pick `applies_to` from an existing tool **handler key** in the same registry (the name used in `<TOOL>handler(args)` / DirectTool resolution).
5. Choose `action`: `confirm`, `block` (or `adjust`), or `escalate`.
6. Write `when` using **only** sensors and flags documented above.
7. Set `priority` relative to related rules (hard blocks &lt; soft confirms for the same tools).
8. Write a short `message` with useful `{placeholders}`.
9. Optionally set `"enabled": false` to land the rule dark until validated.
10. Rebuild / install — **no Kotlin change** if sensors/flags already exist.
11. Add or extend a unit case in `ContextGuardTest` and, when practical, a device case in `ContextGuardInstrumentedTest`.
12. Verify on device with the real cabin state (volume, gear, speed, nav session).

### Handler key reminder

`applies_to` must match the **handler**, not the user utterance and not a skill display name:

| Tool call | Handler for `applies_to` |
|-----------|--------------------------|
| `setVolumeLevel(up)` | `setVolumeLevel` |
| `startNavigationTo("Airport")` | `startNavigationTo` |
| `openTrunk()` | `openTrunk` |

---

## 14. Worked examples

### Confirm volume up when already loud and playing

```json
{
  "id": "volume_already_loud",
  "priority": 10,
  "applies_to": ["setVolumeLevel"],
  "when": {
    "arg_matches": ["up", "increase", "+", "louder", "max"],
    "media_playing": true,
    "sensors": [{ "source": "media_volume_pct", "op": ">=", "value": 85 }]
  },
  "action": "confirm",
  "message": "It's already quite loud ({media_volume_pct}%). Increase anyway?"
}
```

- Matches `setVolumeLevel(up)` at 90% while music plays → confirm.  
- At 40% → no match → allow.  
- Not playing → `media_playing: true` fails → allow (rule skipped).

### Block when already at maximum (higher precedence)

```json
{
  "id": "volume_already_max",
  "priority": 5,
  "applies_to": ["setVolumeLevel"],
  "when": {
    "arg_matches": ["up", "increase", "+", "louder", "max"],
    "sensors": [{ "source": "media_volume_pct", "op": ">=", "value": 100 }]
  },
  "action": "block",
  "message": "Volume is already at maximum ({media_volume_pct}%)."
}
```

Priority `5` beats `volume_already_loud` at `10`, so 100% never asks “increase anyway?” — it refuses.

### Block trunk open while moving

```json
{
  "id": "open_trunk_while_moving",
  "priority": 5,
  "applies_to": ["openTrunk"],
  "when": {
    "sensors": [{ "source": "speed_mph", "op": ">=", "value": 5 }]
  },
  "action": "block",
  "message": "I won't open the trunk while you're moving ({speed_mph} mph)."
}
```

### Confirm unlock while not parked and moving

```json
{
  "id": "unlock_doors_while_moving",
  "priority": 8,
  "applies_to": ["unlockDoors"],
  "when": {
    "sensors": [
      { "source": "is_parked", "op": "==", "value": 0 },
      { "source": "speed_mph", "op": ">=", "value": 5 }
    ]
  },
  "action": "confirm",
  "message": "You're still moving ({speed_mph} mph). Unlock the doors anyway?"
}
```

### Confirm reroute when already navigating elsewhere

```json
{
  "id": "reroute_while_navigating",
  "priority": 11,
  "applies_to": ["startNavigationTo"],
  "when": {
    "nav_active": true,
    "nav_dest_differs_arg": true
  },
  "action": "confirm",
  "message": "You're headed to {nav_active_dest}. Switch destination?"
}
```

Requires `NavSessionState.activeDest` to be set from a prior navigation start.

### Same destination again

```json
{
  "id": "already_navigating_same_dest",
  "priority": 9,
  "applies_to": ["startNavigationTo"],
  "when": {
    "nav_active": true,
    "nav_dest_matches_arg": true
  },
  "action": "confirm",
  "message": "You're already navigating to {nav_active_dest}. Restart that route?"
}
```

### Low fuel before navigation

```json
{
  "id": "low_fuel_navigation",
  "priority": 12,
  "applies_to": ["startNavigationTo"],
  "when": {
    "sensors": [{ "source": "fuel_level_pct", "op": "<=", "value": 15 }]
  },
  "action": "confirm",
  "message": "Fuel is low ({fuel_level_pct}%) near {city}. Start navigation anyway?"
}
```

If fuel is unknown (`-1`), the sensor returns null → rule does **not** match (no false confirm).

### Disable one rule without deleting

```json
{
  "id": "open_windows_while_moving",
  "enabled": false,
  "priority": 10,
  "applies_to": ["openWindowsSlightly", "openWindows"],
  "...": "..."
}
```

---

## 15. Built-in rules (current registry)

Source of truth: `vehicle_skills_registry.json` → `config.context_policies` (`enabled: true`).

| id | Priority | applies_to | when (summary) | Action | Message intent |
|----|----------|------------|----------------|--------|----------------|
| `volume_already_max` | 5 | `setVolumeLevel` | args up-like; `media_volume_pct >= 100` | **block** | Already at maximum |
| `open_trunk_while_moving` | 5 | `openTrunk` | `speed_mph >= 5` | **block** | Won't open trunk while moving |
| `unlock_doors_while_moving` | 8 | `unlockDoors` | `is_parked == 0` AND `speed_mph >= 5` | **confirm** | Still moving — unlock anyway? |
| `already_navigating_same_dest` | 9 | `startNavigationTo` | `nav_active` + dest matches args | **confirm** | Already navigating there — restart? |
| `volume_already_loud` | 10 | `setVolumeLevel` | args up-like; `media_playing`; `media_volume_pct >= 85` | **confirm** | Already loud — increase anyway? |
| `fan_already_max` | 10 | `increaseFanSpeed` | `fan_level >= 7` | **block** | Fan already max |
| `seat_heater_already_on` | 10 | `setSeatHeater` | args `1`/`2`/`3`/`on`; `seat_heater_level >= 2` | **block** | Seat heater already on |
| `open_windows_while_moving` | 10 | `openWindowsSlightly`, `openWindows` | `speed_mph >= 40` | **confirm** | Going ~N mph — open anyway? |
| `set_windows_open_while_moving` | 10 | `setAllWindowsPosition`, `setWindowPosition` | args open-like (`25`/`50`/`75`/`100`/`open`); `speed_mph >= 40` | **confirm** | Going ~N mph — open anyway? |
| `reroute_while_navigating` | 11 | `startNavigationTo` | `nav_active` + dest differs | **confirm** | Headed to X — switch? |
| `low_fuel_navigation` | 12 | `startNavigationTo` | `fuel_level_pct <= 15` | **confirm** | Fuel low near city — navigate anyway? |
| `auto_climate_already_on` | 15 | `turnOnAutoClimate` | `hvac_auto_on == 1` | **block** | Auto climate already on |
| `windows_already_open` | 20 | `openWindowsSlightly` | `window_open_pct >= 20` | **block** | Windows already open |
| `windows_already_closed` | 20 | `closeAllWindows` | `window_open_pct <= 0` | **block** | Windows already closed |

No built-in rule currently uses `action: "escalate"` or `action: "allow"`.

---

## 16. Testing

### Unit tests (JVM)

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tcs.vehicleassistant.core.ContextGuardTest'
```

`ContextGuardTest` injects rules via `ContextGuard.replaceRulesForTest` and asserts Confirm / Block / Allow against synthetic `CabinSnapshot` values (volume, fan, seat, windows, unlock, trunk, fuel, nav same/diff).

### Instrumented (on device / emulator)

```bash
adb shell am instrument -w --user 10 \
  -e class com.tcs.vehicleassistant.requirements.ContextGuardInstrumentedTest \
  com.tcs.vehicleassistant.test/androidx.test.runner.AndroidJUnitRunner
```

Loads real `config.context_policies` through `ToolManager` init and exercises registry-backed evaluations (loud volume confirm, fan max block, low-fuel / reroute, etc.).

### Manual device checks

1. Rebuild/install after JSON edits.  
2. Put the cabin into the matching state (raise volume, start nav, drive gear + speed).  
3. Issue the voice / DirectTool phrase that resolves to the handler in `applies_to`.  
4. Confirm: hear question → say “yes” / “no”.  
5. Block: hear refusal and verify no VHAL / audio write.  
6. Logcat filter: `ContextGuard` (`Policy hit id=…`).

### Test hooks (engine)

| API | Use |
|-----|-----|
| `ContextGuard.replaceRulesForTest(rules, policiesEnabled)` | Inject rules without JSON |
| `ContextGuard.clearRulesForTest()` | Reset to empty / enabled |

Do not call these from production paths.

---

## 17. Troubleshooting

| Symptom | Likely cause |
|---------|----------------|
| Rule never fires | Typo in `applies_to` vs handler key; `when` too strict; sensor unknown (`null`); rule `enabled: false`; master `enabled: false`; assets not rebuilt |
| Wrong rule wins | Another rule with **lower** `priority` matches first |
| Confirm never asked; tool just runs | No matching rule → ALLOW; or policies disabled |
| False block/confirm | Threshold too aggressive; missing `media_playing` / arg filter |
| Window/fuel rules silent | `window_open_pct` / `fuel_level_pct` unknown on this image → sensor null → rule skipped |
| Nav rules silent | `NavSessionState.activeDest` empty (navigation handler never set session) |
| Message shows `{speed_mph}` literally | Typo in placeholder name (must match interpolate map) |
| New sensor in JSON does nothing | Must add Kotlin `CabinSnapshot.sensor` mapping first |

---

## Related docs

- [use_cases/CONTEXT_GUARD.md](use_cases/CONTEXT_GUARD.md) — short architecture map  
- [VEHICLE_SKILLS_REGISTRY_GUIDE.md](VEHICLE_SKILLS_REGISTRY_GUIDE.md) — skills / DirectTool registry (handlers, phrases)

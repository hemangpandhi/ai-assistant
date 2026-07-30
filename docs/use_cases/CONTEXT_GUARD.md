# ContextGuard — registry-driven cabin policies

Gate every tool actuation (DirectTool **and** LLM `<TOOL>`) with declarative rules so the
assistant stays fast *and* cabin-aware (e.g. already-loud music → confirm before raising volume).

**Full OEM / developer how-to** (schema, every `when` clause, sensors, confirm flow, built-in rules, checklist, tests): **[../Policies.md](../Policies.md)**.

## Flow

```text
toolCall proposed
  → CabinSnapshotReader.capture()   // VHAL + Audio + MediaSession + Location + NavSession
  → ContextGuard.evaluate(toolCall, snapshot)
       ALLOW    → execute
       CONFIRM  → speak question; pendingConfirmationTool; "yes" executes
       BLOCK    → speak reason; no write
       ESCALATE → speak facts-only advisory; no write (LLM can still be used later)
```

## Where it lives

| Piece | Path |
|-------|------|
| Snapshot | `core/CabinSnapshot.kt` |
| Nav dest | `core/NavSessionState.kt` (set by `NavigationToolHandler`) |
| Engine | `core/ContextGuard.kt` |
| Policies (JSON only) | `assets/vehicle_skills_registry.json` → `config.context_policies` |
| Load | `ToolManager.initialize` → `ContextGuard.loadFromConfig` |
| DirectTool / FollowUp | `AgentOrchestrator.completeDirectToolTurn` |
| LLM tools | `AgentOrchestrator` tool-execution loop |
| Confirm yes / decline | `AgentOrchestrator.handleQuery` pendingConfirmation fast path |

## Policy authoring (pointer)

- Edit rules in JSON under `config.context_policies.rules` — not Kotlin `if`s, not XML.
- New sensor or new `when` flag → one Kotlin change, then JSON forever.
- Details, examples, and the current built-in rule table: [Policies.md](../Policies.md).

## Tests

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tcs.vehicleassistant.core.ContextGuardTest'
# on device:
adb shell am instrument -w --user 10 \
  -e class com.tcs.vehicleassistant.requirements.ContextGuardInstrumentedTest \
  com.tcs.vehicleassistant.test/androidx.test.runner.AndroidJUnitRunner
```

# mvi/

Stage state for the immersive assistant (intent → reduce → state + effects).

## Flow

1. UI / backend emit `StageIntent`s into `AssistantStageStore`.
2. `reduceStage` produces new `StageState` and `StageEffect`s.
3. Composables collect state; effects drive one-shots (dismiss, focus, etc.).
4. `AssistantStageStoreHolder` wires a process-scoped store when needed.

## Rules

- Keep reducers pure and UI-agnostic (no Compose imports in reduce logic).
- Do not put agent/tool/LLM results parsing here — map those at the backend boundary first.
- Prefer small intents over boolean soup on the state object.

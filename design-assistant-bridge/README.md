# Design assistant import (drop-in copy)

Copied from the Design demo repo for later integration. **Not wired into Gradle yet.**

## What’s here

| Path | Contents |
|------|----------|
| `../assistant/` | Face UI, immersive overlay, gallery, demo backend, overlay services |
| `../assistant-api/` | `AssistantBackend`, `AssistantHost`, `AssistantRuntime`, events/models |
| `DesignAssistantHost.kt` | Example host that supplies cabin context (IVI-specific; rewrite for this app) |
| `../docs/design-assistant/` | Standalone extraction + ADB face-switch notes |

Packages are still `com.test.design.*`.

## Entry points to wire later

1. **Immersive overlay (main widget open):** `VirtualAssistantScreen` → `ImmersiveAssistantOverlay`
2. **Gallery:** `AssistantUiGalleryScreen` / `AssistantUiGalleryActivity`
3. **Runtime install** (Application / DI):
   ```kotlin
   AssistantRuntime.install(
       host = /* your AssistantHost */,
       backend = /* your AssistantBackend or DemoAssistantBackend */,
   )
   ```
4. **Gradle:** `include(":assistant-api")` + `include(":assistant")`, then `implementation(project(":assistant"))` from `:app` (and align Compose / SDK with this project’s catalog).

See `../docs/design-assistant/assistant-standalone.md` for the intended module boundary.

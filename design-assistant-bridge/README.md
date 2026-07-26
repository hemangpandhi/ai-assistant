# Design assistant import

The Compose assistant libraries live in `:assistant` + `:assistant-api` and are wired into `:app`.

Host implementation (replaces the Design-demo stub):

- `app/.../assistant/VehicleAssistantHost.kt`
- `app/.../assistant/AssistantRuntimeBootstrap.kt`
- `app/.../assistant/AssistantUiProfile.kt`

ADB docs: [`../docs/design-assistant/assistant-adb.md`](../docs/design-assistant/assistant-adb.md)

`DesignAssistantHost.kt` in this folder is a Design-demo reference only and is **not** compiled.

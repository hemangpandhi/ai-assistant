# Assistant module — standalone extraction

The immersive assistant is split so UI/session chrome never depends on IVI vehicle code.

## Modules

| Module | Role |
|--------|------|
| `:assistant-api` | Pure contracts: `AssistantBackend`, `AssistantHost`, session events/models. No Compose. |
| `:assistant` | Face UI, overlay service, demo backend, STT/TTS adapters. Depends only on `:assistant-api`. |
| `:app` | Implements `AssistantHost` (`DesignAssistantHost`), installs runtime in `DesignApplication`, publishes cabin context from `DesignAppShell`. |

## Wiring today

```kotlin
AssistantRuntime.install(
    host = DesignAssistantHost(app),
    backend = DemoAssistantBackend(speakingTts = platformAssistantTts(app)),
)
```

`ImmersiveAssistantOverlay` collects `AssistantBackend.events` and forwards mic input via `onSpeechInput`. Swap `DemoAssistantBackend` for a remote/LLM client without touching Compose.

## Standalone APK path

1. New application module depends on `:assistant` (+ `:assistant-api` transitively).
2. Implement `AssistantHost` (cabin snapshot + optional cluster hand-off).
3. Call `AssistantRuntime.install(host, backend)` in `Application.onCreate`.
4. Launch `VirtualAssistantActivity` / `ImmersiveAssistantOverlayService` (move manifest entries from `:app` when ready).
5. Provide `LocalAssistantChromeBottomSpace` if the host has a floating dock; default is `0.dp`.

## Keep decoupled

- Do not import `VehicleViewModel`, `DrivingUxState`, or `DesignAppShell` from `:assistant`.
- Cabin facts cross the boundary only as `AssistantCabinContext` strings/numbers.
- Theme via `AssistantTheme` inside the assistant module; host OEM theme is optional.

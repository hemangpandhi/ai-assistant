# Assistant module — standalone extraction

The immersive assistant is split so UI/session chrome never depends on IVI vehicle code.

## Modules

| Module | Role |
|--------|------|
| `:assistant-api` | Pure contracts: `AssistantBackend`, `AssistantHost`, session events/models. No Compose. |
| `:assistant` | Face UI, overlay service, demo backend, STT/TTS adapters. Depends only on `:assistant-api`. |
| `:app` | Implements `AssistantHost` (`VehicleAssistantHost`), installs runtime in `VehicleApplication` via `AssistantRuntimeBootstrap`. |

Full UI/UX + ADB guide: [ASSISTANT_UI_UX.md](./ASSISTANT_UI_UX.md).

## Package layout

Packages align with the Android `namespace` (`com.test.design.assistant` / `…assistant.api`):

```text
:assistant-api
  com.test.design.assistant.api/          # AssistantBackend, AssistantHost, AssistantRuntime
  com.test.design.assistant.api/model/    # events + models (package stays …assistant.api)

:assistant
  …assistant.api consumers live under:
  audio/       STT, TTS, wake feedback
  backend/     DemoAssistantBackend + mood mapping
  dialogue/    scripts + playback
  entry/       VirtualAssistantActivity / overlay entry
  face/        moods, faces, face config / receiver
  ui/theme/    AssistantTheme + tokens
  ui/chrome/   shared chrome helpers (waveform, presence, props)
  ui/immersive/ immersive stage + overlay service
  ui/overlay/  floating overlay service
  ui/gallery/  UI variant gallery
```

## Wiring today

```kotlin
AssistantRuntimeBootstrap.install(app, useDemoBackend = true)
// → AssistantRuntime.install(VehicleAssistantHost(app), DemoAssistantBackend(...))
```

`ImmersiveAssistantOverlay` collects `AssistantBackend.events` and forwards mic input via `onSpeechInput`. Swap `DemoAssistantBackend` for `VehicleAgentAssistantBackend` without touching Compose.

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

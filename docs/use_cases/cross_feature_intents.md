# Use-Case: Cross-Feature Intents & Media Controls

This diagram details the sequence when the LLM generates a tool call intended for another Android application. It highlights the use of standard Android Intents, the `MediaBrowserService`, and `AudioManager` for deep OS-level integration.

```mermaid
sequenceDiagram
    autonumber
    participant LLM as LiteRT Engine
    participant AS as AssistantSession
    participant TM as ToolManager
    participant PKG as PackageManager (AOSP)
    participant AUDIO as AudioManager (AOSP)
    participant MEDIA as MediaBrowserService (Spotify/YT Music)
    participant MAPS as Google Maps / Navigation
    participant PHONE as Dialer App

    LLM-->>AS: Token Stream `<TOOL>playMusic(Jazz)</TOOL>`
    
    AS->>AS: Regex Extract "playMusic(Jazz)"
    AS->>TM: executeToolCall(context, "playMusic(Jazz)")
    
    TM->>PKG: queryIntentServices("android.media.browse.MediaBrowserService")
    PKG-->>TM: List of active Media Providers
    
    TM->>TM: Select preferred provider (e.g., Spotify)
    
    TM->>MEDIA: connect() via MediaBrowser
    MEDIA-->>TM: onConnected() (Returns sessionToken)
    
    TM->>MEDIA: MediaController.transportControls.playFromSearch("Jazz", null)
    MEDIA->>MEDIA: Starts playing requested music
    
    TM-->>AS: Return "Playing Jazz."
    
    %% Next Track Flow
    note over LLM, AUDIO: Next Track Flow
    
    LLM-->>AS: Token Stream `<TOOL>nextTrack()</TOOL>`
    AS->>TM: executeToolCall(context, "nextTrack()")
    
    TM->>AUDIO: getSystemService(Context.AUDIO_SERVICE)
    TM->>AUDIO: dispatchMediaKeyEvent(ACTION_DOWN, KEYCODE_MEDIA_NEXT)
    TM->>AUDIO: dispatchMediaKeyEvent(ACTION_UP, KEYCODE_MEDIA_NEXT)
    
    AUDIO->>MEDIA: Forwards KeyEvent to active MediaSession
    TM-->>AS: Return "Playing next track."

    %% Navigation Flow
    note over LLM, MAPS: Navigation Flow
    
    LLM-->>AS: Token Stream `<TOOL>navigate(Home)</TOOL>`
    AS->>TM: executeToolCall(context, "navigate(Home)")
    
    TM->>TM: Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=Home"))
    TM->>MAPS: context.startActivity(intent)
    TM-->>AS: Return "Routing to Home."
    
    %% Phone Call Flow
    note over LLM, PHONE: Phone Call Flow
    
    LLM-->>AS: Token Stream `<TOOL>call(Mechanic)</TOOL>`
    AS->>TM: executeToolCall(context, "call(Mechanic)")
    
    TM->>TM: SharedPreferences.getString("mechanic_name") -> maps to phone number
    TM->>TM: Intent(Intent.ACTION_DIAL, Uri.parse("tel:1234567890"))
    TM->>PHONE: context.startActivity(intent)
    TM-->>AS: Return "Calling Mechanic."
```

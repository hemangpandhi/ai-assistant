# Use-Case: End-to-End Voice Interaction (Local Inference)

This sequence diagram illustrates the data flow for a standard voice command processed entirely offline by the local LLM.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant VWS as WakeWordService (Vosk)
    participant AS as AssistantSession
    participant MM as MemoryManager
    participant LLM as LLMManager
    participant LITE as LiteRT Engine
    participant TTS as TextToSpeech

    User->>VWS: Speaks "Hey Auto"
    VWS->>AS: Trigger Intent (ACTION_VOICE_COMMAND)
    AS->>AS: Show Voice Overlay UI
    AS->>User: Play wake chime
    User->>AS: Speaks command (e.g. "What's the weather?")
    AS->>AS: SpeechRecognizer transcription
    AS->>MM: addTurn("User", query)
    
    AS->>LLM: getDynamicContext()
    LLM-->>AS: Return tool & state context
    
    AS->>LITE: sendMessageAsync(finalPrompt)
    
    loop Token Streaming
        LITE-->>AS: onMessage(chunk)
        AS->>AS: Append chunk & Update UI Typwriter
        AS->>TTS: chunk via Regex boundary (e.g. on '.')
        TTS->>User: Audio playback (Synthesized Speech)
    end
    
    LITE-->>AS: onDone()
    AS->>MM: addTurn("Assistant", finalResponse)
```

# Use-Case: Cloud Fallback Execution (SSE Streaming)

This sequence diagram illustrates the flow when the local edge model is disabled, and the system relies on Cloud APIs (Gemini/Claude). It highlights the use of Server-Sent Events (SSE) to maintain low-latency streaming to the UI.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as LocalLLMActivity / AssistantSession
    participant CLOUD as GeminiManager / AnthropicManager
    participant HTTP as OkHttp Client
    participant API as Google/Anthropic Cloud

    User->>UI: Speaks/Types prompt
    
    UI->>UI: Check isCloudModelActive == true
    
    UI->>CLOUD: sendMessageAsync(systemPrompt, query, CloudMessageCallback)
    
    CLOUD->>HTTP: Build HTTP Request (stream=true)
    HTTP->>API: POST /generateContent
    
    loop Server-Sent Events (SSE)
        API-->>HTTP: TCP Socket chunk (data: {...})
        HTTP-->>CLOUD: EventSourceListener.onEvent()
        CLOUD->>CLOUD: Parse JSON chunk for text delta
        CLOUD->>UI: CloudMessageCallback.onMessage(chunk)
        UI->>UI: Update UI Typewriter / TTS Audio Buffer
    end
    
    API-->>HTTP: TCP Socket closed ([DONE])
    HTTP-->>CLOUD: EventSourceListener.onClosed()
    CLOUD->>UI: CloudMessageCallback.onDone()
```

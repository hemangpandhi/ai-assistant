# Use-Case: Recursive Agentic Loop (Local Discovery)

This sequence diagram details the architecture's Agentic Loop. It explicitly models how `ToolManager` fetches external HTTP data and how `AssistantSession` manipulates the `isAgenticObservation` state to silently feed data back to the LLM.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant AS as AssistantSession
    participant LITE as LiteRT Engine
    participant TM as ToolManager
    participant HTTP as OkHttpClient
    participant API as Nominatim REST API
    
    User->>AS: Speaks "I'm hungry, show me Italian places."
    
    AS->>LITE: sendMessageAsync("User: I'm hungry...")
    
    loop Prefill / Generation
        LITE-->>AS: Token chunks
    end
    LITE-->>AS: Generates `<TOOL>search(italian restaurant)</TOOL>`
    
    AS->>AS: Intercepts XML tag (Suppresses UI Typewriter & TTS)
    AS->>TM: executeToolCall(context, "search(italian restaurant)")
    
    TM->>HTTP: Request.Builder().url("https://nominatim.openstreetmap.org/search...").build()
    HTTP->>API: GET Request
    API-->>HTTP: JSON Payload (Places & Coordinates)
    HTTP-->>TM: Response Body (String)
    
    TM->>TM: Parse JSON & extract Top 5 locations
    TM-->>AS: ToolFeedback: "Found: 1. Pizza Hut, 2. Trattoria..."
    
    AS->>AS: isAgenticObservation = true
    AS->>LITE: sendMessageAsync("System: Executed search. Result: Found Pizza Hut... User originally said 'yes'.")
    
    loop Agentic Reasoning Generation
        LITE-->>AS: Token chunks
        AS->>AS: Stream to UI and TextToSpeech
    end
    
    LITE-->>AS: Generates: "I found Pizza Hut nearby. Would you like to navigate there?"
    AS->>AS: isAgenticObservation = false
```

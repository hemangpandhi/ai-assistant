# Use-Case: Recursive Agentic Loop (Local Discovery)

This sequence diagram details the architecture's Agentic Loop. It triggers when the local LLM needs to use an external tool to fetch data before generating a final response (e.g., querying for nearby restaurants).

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant AS as AssistantSession
    participant LITE as LiteRT Engine
    participant TM as ToolManager
    participant HTTP as Nominatim API
    
    User->>AS: Speaks "I'm hungry, show me Italian places."
    
    AS->>LITE: sendMessageAsync("User: I'm hungry...")
    
    loop Prefill / Generation
        LITE-->>AS: Token chunks
    end
    LITE-->>AS: Generates `<TOOL>search(italian restaurant)</TOOL>`
    
    AS->>AS: Intercepts XML tag (Suppresses from UI/TTS)
    AS->>TM: executeToolCall(context, "search(italian restaurant)")
    
    TM->>HTTP: GET /search?q=italian+restaurant+Sagamihara
    HTTP-->>TM: JSON Payload (Places & Coordinates)
    TM-->>AS: ToolFeedback: "Found: 1. Pizza Hut, 2. Trattoria..."
    
    AS->>AS: isAgenticObservation = true
    AS->>LITE: sendMessageAsync("System: Executed search. Result: Found Pizza Hut...")
    
    loop Agentic Reasoning Generation
        LITE-->>AS: Token chunks
        AS->>AS: Stream to UI and TTS
    end
    
    LITE-->>AS: Generates: "I found Pizza Hut nearby. Would you like to navigate there?"
```

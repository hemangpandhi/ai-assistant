# Use-Case: End-to-End Voice Interaction (Local Inference)

This sequence diagram illustrates the data flow for a standard voice command processed entirely offline by the local LLM. It maps the exact Android Speech APIs and the C++ JNI bridge to the LiteRT engine.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant VWS as WakeWordService (Vosk)
    participant AS as AssistantSession
    participant STT as android.speech.<br/>SpeechRecognizer
    participant MM as MemoryManager
    participant LLM as LLMManager
    participant LITE as LiteRT Engine<br/>(liblitertlm_jni.so)
    participant TTS as android.speech.tts.<br/>TextToSpeech

    User->>VWS: Speaks "Hey Auto" (Acoustic Match)
    VWS->>AS: Context.startSession() / Intent(ACTION_VOICE_COMMAND)
    AS->>AS: Show Voice Overlay UI
    AS->>User: Play wake chime
    
    AS->>STT: startListening(RecognizerIntent)
    User->>STT: Speaks command (e.g. "What's the weather?")
    STT-->>AS: onResults() -> Transcribed String
    
    AS->>MM: addTurn("User", query)
    
    AS->>LLM: LLMManager.getDynamicContext()
    LLM->>LLM: Compiles System Prompt + vehicle_skills_registry.json tools
    LLM-->>AS: Returns full context prompt
    
    AS->>LITE: sendMessageAsync(finalPrompt)
    LITE->>LITE: Native C++ GPU/NPU Prefill & Generation
    
    loop Token Streaming
        LITE-->>AS: MessageCallback.onMessage(chunk)
        AS->>AS: Append chunk & Update UI Typwriter
        AS->>AS: Regex eval: `(?<=[a-z])[.!?](?:\s+|$)`
        AS->>TTS: TextToSpeech.speak(sentence, QUEUE_ADD, null)
        TTS->>User: Audio playback (Synthesized Speech)
    end
    
    LITE-->>AS: MessageCallback.onDone()
    AS->>MM: addTurn("Assistant", finalResponse)
```

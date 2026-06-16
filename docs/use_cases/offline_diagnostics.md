# Use-Case: Offline Hardware Diagnostics (/diagnostics)

This sequence diagram illustrates the debugging flow used by engineers to map unconfigured AOSP/Vendor Area IDs without compiling custom apps. It triggers a dry-run iteration over all defined tools.

```mermaid
sequenceDiagram
    autonumber
    actor Engineer
    participant ACT as LocalLLMActivity
    participant TM as ToolManager
    participant VM as VehicleManager
    participant CPM as CarPropertyManager

    Engineer->>ACT: Types "/diagnostics" in Chat UI
    
    ACT->>ACT: Bypasses LLM Generation
    ACT->>TM: runSystemDiagnostics(context)
    
    loop For each Tool in vehicle_skills_registry.json
        TM->>TM: Generate dummy payload (e.g., "$key(100)")
        TM->>VM: executeToolCall(context, dummyCall)
        VM->>CPM: setProperty()
        
        alt Success
            CPM-->>VM: Returns void
            VM-->>TM: Append "✅ PASS" to Markdown Report
        else Hardware Unmapped / Crashes
            CPM-->>VM: Throws IllegalArgumentException
            VM-->>TM: Append "❌ CRASH" to Markdown Report
        end
    end
    
    TM->>VM: runPropertyDiagnostics()
    
    loop For each Sensor Property
        VM->>CPM: getProperty()
        CPM-->>VM: Return Read Status
        VM-->>VM: Append to Markdown Report
    end
    
    VM-->>TM: Return Full Sensor Report
    TM-->>ACT: Return concatenated Markdown Report
    ACT->>ACT: Render Markdown Table in ChatRecyclerView
```

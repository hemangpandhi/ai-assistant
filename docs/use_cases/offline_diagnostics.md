# Use-Case: Offline Hardware Diagnostics (/diagnostics)

This sequence diagram illustrates the debugging flow used to map unconfigured AOSP/Vendor Area IDs without compiling custom apps. It triggers a dry-run iteration over all defined JSON tools and actively polls the CarPropertyManager.

```mermaid
sequenceDiagram
    autonumber
    actor Engineer
    participant ACT as LocalLLMActivity
    participant TM as ToolManager
    participant VM as VehicleManager
    participant CPM as android.car.hardware.property.<br/>CarPropertyManager

    Engineer->>ACT: Types "/diagnostics" (auto-converted to lowercase) in Chat UI
    
    ACT->>ACT: Bypasses LLM Generation
    ACT->>TM: ToolManager.runSystemDiagnostics(context)
    
    loop For each Tool in vehicle_skills_registry.json
        TM->>TM: Generate dummy payload (e.g., "$key(100)")
        TM->>VM: ToolManager.executeToolCall(context, dummyCall)
        VM->>CPM: CarPropertyManager.setProperty(...)
        
        alt Success
            CPM-->>VM: Hardware ACK
            VM-->>TM: Append "✅ PASS" to Markdown Report
        else Hardware Unmapped / Crashes
            CPM-->>VM: Throws IllegalArgumentException or SecurityException
            VM-->>TM: Append "❌ CRASH" to Markdown Report
        end
    end
    
    TM->>VM: VehicleManager.runPropertyDiagnostics()
    
    loop For each Sensor Property
        VM->>CPM: CarPropertyManager.getCarPropertyConfig(propertyId)
        
        alt Config Exists
            CPM-->>VM: Returns CarPropertyConfig (contains Area IDs)
            VM->>CPM: CarPropertyManager.getProperty(propertyId, areaId)
            CPM-->>VM: Returns CarPropertyValue (Float/Int/Boolean)
            VM-->>VM: Append "✅ Read successful: [Value]"
        else Config Null
            CPM-->>VM: null
            VM-->>VM: Append "⚠️ UNSUPPORTED"
        end
    end
    
    VM-->>TM: Return Full Sensor Report (String)
    TM-->>ACT: Return concatenated Markdown Report
    ACT->>ACT: ChatAdapter renders Markdown Table in RecyclerView
```

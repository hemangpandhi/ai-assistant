# Use-Case: Dynamic VHAL Hardware Actuation

This sequence diagram illustrates the data flow when the local AI Assistant executes a physical vehicle command. It details the initialization of the `vehicle_skills_registry.json`, the parsing of the `<TOOL>` tag, and the deep traversal through the Android OS layers down to the physical Vehicle HAL.

```mermaid
sequenceDiagram
    autonumber
    participant JSON as vehicle_skills_registry.json
    participant AS as AssistantSession
    participant TM as ToolManager
    participant VM as VehicleManager
    participant CPM as android.car.hardware.property.<br/>CarPropertyManager
    participant CS as com.android.car.<br/>CarService (IPC)
    participant VHAL as IVehicle (AIDL/HIDL)<br/>Vehicle HAL

    %% Initialization Phase
    note over JSON, TM: System Boot / Initialization
    JSON-->>TM: Load "tools" array on boot
    TM->>TM: Parse JSON into activeTools Map
    
    %% Execution Phase
    note over AS, VHAL: Tool Execution Phase
    
    AS->>AS: Parses `<TOOL>setTemperature(70)</TOOL>` from LLM output
    AS->>TM: executeToolCall(context, "setTemperature(70)")
    
    TM->>TM: Lookup "setTemperature" in activeTools Map
    TM->>TM: Identifies handler_type == "GENERIC_VHAL_WRITE"
    TM->>TM: Extracts property_id (e.g., 358614275) & data_type ("FLOAT")
    
    TM->>VM: setPropertyVerified(property_id, area_id, "70.0", "FLOAT")
    
    VM->>CPM: getCarPropertyConfig(property_id)
    CPM-->>VM: Returns valid Area IDs and Config metadata
    
    VM->>CPM: setProperty(Float::class.java, property_id, area_id, 70.0f)
    
    %% Android Framework to Hardware
    CPM->>CS: Cross-Process Binder IPC
    CS->>VHAL: AIDL/HIDL Hardware Bridge Translation
    VHAL->>VHAL: Native C++ Electrical Actuation (CAN Bus)
    VHAL-->>CS: Hardware ACK
    CS-->>CPM: Return execution status
    
    CPM-->>VM: Return Success (Boolean)
    
    VM-->>TM: Return Boolean
    TM->>TM: Lookup JSON "success_message"
    TM-->>AS: Return "I've set the temperature to 70 degrees."
    
    AS->>AS: Update Dashboard UI state
    AS->>AS: Push success message to TTS audio buffer
```

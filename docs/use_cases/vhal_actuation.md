# Use-Case: Dynamic VHAL Hardware Actuation

This sequence diagram illustrates the data flow when the local AI Assistant executes a physical vehicle command (e.g., turning on the AC or rolling down windows) by communicating with the Android Car Service and the underlying Vehicle HAL.

```mermaid
sequenceDiagram
    autonumber
    participant AS as AssistantSession
    participant TM as ToolManager
    participant VM as VehicleManager
    participant CPM as CarPropertyManager (AOSP)
    participant VHAL as Vehicle HAL
    
    AS->>AS: Parses `<TOOL>setTemperature(70)</TOOL>` from LLM output
    AS->>TM: executeToolCall(context, "setTemperature(70)")
    
    TM->>TM: Regex parses "setTemperature" handler
    TM->>VM: writeTemperatureToVhalVerified(70.0)
    
    VM->>CPM: getCarPropertyConfig(HVAC_TEMPERATURE_SET)
    CPM-->>VM: Returns valid areaIds and max/min bounds
    
    VM->>VM: Converts 70F to Celsius (if required by VHAL)
    
    VM->>CPM: setProperty(PropertyId, AreaId, Value)
    CPM->>VHAL: HIDL / AIDL Binder Transaction
    VHAL-->>CPM: Hardware ACK
    CPM-->>VM: Return Success (Boolean)
    
    VM-->>TM: Return Boolean
    TM-->>AS: Return "I've set the temperature to 70 degrees."
    
    AS->>AS: Update Dashboard UI state
    AS->>AS: Push success message to TTS buffer
```

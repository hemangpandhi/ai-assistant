import json

path = "app/src/main/assets/vehicle_skills_registry.json"
with open(path, "r") as f:
    data = json.load(f)

# Tools that definitely need vehicle state
state_tools = {
    "setTemperature", "increaseTemperature", "decreaseTemperature", 
    "setFanSpeed", "setSeatHeater", "setSeatMassager", "openWindow", 
    "closeWindow", "navigate", "controlHomeDevice", "checkTirePressure",
    "checkBattery", "getRange", "checkDoors"
}

for tool in data["tools"]:
    handler = tool.get("handler_key", "")
    if handler in state_tools or "GENERIC_VHAL" in tool.get("handler_type", ""):
        tool["requires_vehicle_state"] = True
    else:
        tool["requires_vehicle_state"] = False

with open(path, "w") as f:
    json.dump(data, f, indent=4)
print(f"Updated {len(data['tools'])} tools.")

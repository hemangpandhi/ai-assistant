import json

filepath = "app/src/main/assets/vehicle_skills_registry.json"
with open(filepath, 'r') as f:
    data = json.load(f)

for tool in data.get("tools", []):
    # Add a fallback error_message for GENERIC_VHAL_WRITE tools or specific handlers
    if tool.get("handler_type") == "GENERIC_VHAL_WRITE" or tool.get("handler_key") in ["increaseTemperature", "decreaseTemperature", "setTemperature"]:
        tool["error_message"] = "The vehicle hardware did not confirm the change."

with open(filepath, 'w') as f:
    json.dump(data, f, indent=4)

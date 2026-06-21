import json

filepath_v2 = "app/src/main/assets/vehicle_skills_registry_v2.0.json"
try:
    with open(filepath_v2, 'r') as f:
        data = json.load(f)
except FileNotFoundError:
    print(f"File not found: {filepath_v2}")
    exit(1)

for tool in data.get("tools", []):
    # Add a fallback error_message for GENERIC_VHAL_WRITE tools or specific handlers
    if tool.get("handler_type") == "GENERIC_VHAL_WRITE" or tool.get("handler_key") in ["increaseTemperature", "decreaseTemperature", "setTemperature"]:
        if "error_message" not in tool:
            tool["error_message"] = "The vehicle hardware did not confirm the change."

with open(filepath_v2, 'w') as f:
    json.dump(data, f, indent=4)
print("Updated v2.0 JSON successfully.")

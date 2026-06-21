import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

tools = data.get("tools", [])

new_tool = {
    "prompt_string": "<TOOL>getBatteryLevel()</TOOL>",
    "handler_key": "getBatteryLevel",
    "handler_type": "NATIVE_VHAL",
    "keywords": [
        "battery",
        "range",
        "how much battery",
        "battery level",
        "charge"
    ],
    "offline_capable": True,
    "success_message": "Checking the battery level.",
    "error_message": "Failed to read battery.",
    "requires_vehicle_state": True
}

tools.append(new_tool)

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

print("getBatteryLevel injected into registry!")

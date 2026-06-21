import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

tools = data.get("tools", [])

new_tool = {
    "prompt_string": "<TOOL>adjustSeatPosition(direction)</TOOL>",
    "handler_key": "adjustSeatPosition",
    "handler_type": "NATIVE_VHAL",
    "keywords": [
        "seat",
        "position",
        "sleepy",
        "alertness"
    ],
    "offline_capable": True,
    "success_message": "Adjusting seat for better alertness.",
    "error_message": "Failed to move seat.",
    "requires_vehicle_state": True
}

tools.append(new_tool)

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

print("adjustSeatPosition injected into registry!")

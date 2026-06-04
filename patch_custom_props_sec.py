import json

with open("app/src/main/assets/custom_properties.json", "r") as f:
    data = json.load(f)

data["tools"].append({
    "prompt_string": "<TOOL>openTrunk()</TOOL>",
    "handler_key": "openTrunk",
    "handler_type": "GENERIC_VHAL_WRITE",
    "property_id": 320865540, 
    "data_type": "BOOLEAN",
    "area_id": 0,
    "value_to_write": "true",
    "success_message": "I've popped the trunk.",
    "keywords": ["trunk", "open", "back", "boot", "cargo"],
    "requires_confirmation": True,
    "confirmation_message": "Warning: Are you sure you want to open the trunk?"
})

data["tools"].append({
    "prompt_string": "<TOOL>unlockDoors()</TOOL>",
    "handler_key": "unlockDoors",
    "handler_type": "GENERIC_VHAL_WRITE",
    "property_id": 320865541, 
    "data_type": "BOOLEAN",
    "area_id": 0,
    "value_to_write": "true",
    "success_message": "I've unlocked the doors.",
    "keywords": ["unlock", "door", "doors", "open", "latch"],
    "requires_confirmation": True,
    "confirmation_message": "Security Warning: Are you sure you want to unlock the vehicle doors?"
})

with open("app/src/main/assets/custom_properties.json", "w") as f:
    json.dump(data, f, indent=2)

print("custom_properties.json patched for security.")

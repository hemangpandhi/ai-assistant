import json

with open("app/src/main/assets/vehicle_skills_registry.json") as f:
    old = json.load(f)

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    new = json.load(f)

# Build map by handler_key (or prompt_string if handler_key is missing)
def get_key(tool):
    return tool.get("handler_key") or tool.get("prompt_string")

old_map = {get_key(t): t for t in old.get("tools", [])}
new_map = {get_key(t): t for t in new.get("tools", [])}

missing_tools = set(old_map.keys()) - set(new_map.keys())
print(f"Tools missing in v2: {missing_tools}")

# Add missing tools
for key in missing_tools:
    new["tools"].append(old_map[key])
    new_map[key] = old_map[key]

# Merge missing fields
fields_added_count = 0
for key in new_map:
    if key in old_map:
        old_tool = old_map[key]
        new_tool = new_map[key]
        for field, value in old_tool.items():
            if field not in new_tool:
                new_tool[field] = value
                fields_added_count += 1

print(f"Added {fields_added_count} missing fields to existing v2 tools.")

# Add error_message for GENERIC_VHAL_WRITE and HVAC
for tool in new["tools"]:
    if tool.get("handler_type") == "GENERIC_VHAL_WRITE" or tool.get("handler_key") in ["increaseTemperature", "decreaseTemperature", "setTemperature"]:
        if "error_message" not in tool:
            tool["error_message"] = "The vehicle hardware did not confirm the change."

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(new, f, indent=4)

print("Smart merge complete.")

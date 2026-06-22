import json

filepath = "app/src/main/assets/vehicle_skills_registry_v2.0.json"
with open(filepath) as f:
    data = json.load(f)

tools = data.get("tools", [])
used_property_ids = set()
for tool in tools:
    if "property_id" in tool:
        used_property_ids.add(tool["property_id"])

properties = data.get("properties", [])
filtered_properties = [p for p in properties if p["id"] in used_property_ids]

data["properties"] = filtered_properties

with open(filepath, "w") as f:
    json.dump(data, f, indent=4)

print(f"Reduced properties from {len(properties)} to {len(filtered_properties)}")

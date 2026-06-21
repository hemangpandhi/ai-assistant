import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

tools = data.get("tools", [])
used_property_ids = set()
for tool in tools:
    if "property_id" in tool:
        used_property_ids.add(tool["property_id"])

print(f"Total tools: {len(tools)}")
print(f"Tools with property_id: {sum(1 for t in tools if 'property_id' in t)}")
print(f"Unique used property_ids: {len(used_property_ids)}")

properties = data.get("properties", [])
print(f"Total properties in JSON: {len(properties)}")

# Find which ones are unused
unused_props = [p for p in properties if p["id"] not in used_property_ids]
print(f"Unused properties: {len(unused_props)}")


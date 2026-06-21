import json

with open("app/src/main/assets/vehicle_skills_registry.json") as f:
    old = json.load(f)

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    new = json.load(f)

# Compare top level keys
print(f"Old keys: {old.keys()}")
print(f"New keys: {new.keys()}")

old_props = {p["id"]: p for p in old.get("properties", [])}
new_props = {p["id"]: p for p in new.get("properties", [])}

print(f"Old properties count: {len(old_props)}")
print(f"New properties count: {len(new_props)}")

# Find properties missing in new
missing_props = set(old_props.keys()) - set(new_props.keys())
print(f"Properties missing in v2: {missing_props}")

old_tools = {t["prompt_string"]: t for t in old.get("tools", [])}
new_tools = {t["prompt_string"]: t for t in new.get("tools", [])}

print(f"Old tools count: {len(old_tools)}")
print(f"New tools count: {len(new_tools)}")

# Find tools missing in new
missing_tools = set(old_tools.keys()) - set(new_tools.keys())
print(f"Tools missing in v2:")
for k in missing_tools:
    print(f" - {k}")

# Check for fields missing in new tools that exist in old tools
fields_missing = set()
for k in set(old_tools.keys()).intersection(set(new_tools.keys())):
    for field in old_tools[k]:
        if field not in new_tools[k]:
            fields_missing.add((k, field))

if fields_missing:
    print("Fields missing in v2 tools:")
    for k, f in fields_missing:
        print(f" Tool: {k}, Missing Field: {f}")


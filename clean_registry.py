import json

path = "app/src/main/assets/vehicle_skills_registry_2.json"
with open(path, "r") as f:
    data = json.load(f)

# Remove 'properties' array entirely
if "properties" in data:
    del data["properties"]

# Load old tools to find any other missing ones
with open("old_registry.json", "r") as f:
    old_data = json.load(f)

old_tools = old_data.get("tools", [])

new_tools = data.get("tools", [])
new_tool_keys = [t.get("handler_key") for t in new_tools if "handler_key" in t]

for old_tool in old_tools:
    key = old_tool.get("handler_key")
    if key and key not in new_tool_keys:
        print(f"Adding missing tool: {key}")
        new_tools.append(old_tool)

data["tools"] = new_tools

with open(path, "w") as f:
    json.dump(data, f, indent=4)

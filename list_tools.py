import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

for tool in data.get("tools", []):
    key = tool.get("handler_key") or tool.get("prompt_string")
    print(key)

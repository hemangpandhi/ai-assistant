import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

for tool in data["tools"]:
    if "AC" in tool.get("prompt_string", ""):
        print(tool["prompt_string"], tool["keywords"])

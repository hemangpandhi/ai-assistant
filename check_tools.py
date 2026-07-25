import json

REGISTRY_PATH = "app/src/main/assets/vehicle_skills_registry.json"
with open(REGISTRY_PATH, 'r') as f:
    registry = json.load(f)

for t in registry["tools"]:
    if "(" in t["prompt_string"] and "()" not in t["prompt_string"]:
        print(t["prompt_string"])

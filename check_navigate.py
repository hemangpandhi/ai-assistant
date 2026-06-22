import json

with open('app/src/main/assets/vehicle_skills_registry_v2.0.json', 'r') as f:
    registry = json.load(f)

for tool in registry:
    if tool.get('handler_key') == 'navigate':
        print(json.dumps(tool, indent=2))

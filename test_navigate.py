import sys
import json
import re

with open('app/src/main/assets/vehicle_skills_registry_v2.0.json', 'r') as f:
    registry = json.load(f)

for category in registry['categories']:
    for tool in category['tools']:
        if tool['handler_key'] == 'navigate':
            print("Navigate Tool Context:")
            print(json.dumps(tool, indent=2))

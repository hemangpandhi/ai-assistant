import json

with open('app/src/main/assets/vehicle_skills_registry_v2.0.json', 'r') as f:
    data = json.load(f)

for tool in data.get('tools', []):
    key = tool.get('handler_key')
    if key == 'increaseTemperature':
        tool['keywords'] = ["warmer", "hotter", "turn up", "freezing", "cold", "increase"]
    elif key == 'decreaseTemperature':
        tool['keywords'] = ["cooler", "turn down", "ac", "sweating", "hot", "decrease"]

with open('app/src/main/assets/vehicle_skills_registry_v2.0.json', 'w') as f:
    json.dump(data, f, indent=4)

print("Sanitized keywords.")

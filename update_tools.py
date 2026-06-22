import json

with open('app/src/main/assets/vehicle_skills_registry.json', 'r') as f:
    data = json.load(f)

emulated_bypass_ids = [289410577, 354419973, 320865540, 354419978, 354419982, 354419984, 322964416]

for tool in data.get('tools', []):
    if tool.get('property_id') in emulated_bypass_ids:
        tool['emulated_bypass'] = True

    # Add diagnostic_payload based on old hardcoded logic
    prompt = tool.get('prompt_string', '')
    if 'VAL' in prompt:
        tool['diagnostic_payload'] = "72.0"
    elif 'LEVEL' in prompt:
        tool['diagnostic_payload'] = "1"
    elif 'PCT' in prompt:
        tool['diagnostic_payload'] = "50"
    elif 'DEST' in prompt:
        tool['diagnostic_payload'] = "Home"
    elif 'SONG' in prompt:
        tool['diagnostic_payload'] = "Test"
    elif 'NAME' in prompt:
        tool['diagnostic_payload'] = "Mechanic"
    elif 'FACT' in prompt:
        tool['diagnostic_payload'] = "TestFact"

with open('app/src/main/assets/vehicle_skills_registry.json', 'w') as f:
    json.dump(data, f, indent=2)


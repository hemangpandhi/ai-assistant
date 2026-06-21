import json

with open("app/src/main/assets/vehicle_skills_registry.json", "r") as f:
    data = json.load(f)

print(f"Number of system instructions: {len(data.get('system_instructions', []))}")
for idx, instruction in enumerate(data.get('system_instructions', [])):
    print(f"Instruction {idx}:")
    print(f"  keywords: {instruction.get('keywords', [])}")
    print(f"  instruction length: {len(instruction.get('instruction', ''))}")

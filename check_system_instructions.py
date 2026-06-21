import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

print(f"System instructions keys: {data.keys()}")
if "system_instructions" in data:
    instructions = data["system_instructions"]
    print(f"Total system instructions: {len(instructions)}")
    total_len = sum(len(i.get("instruction", "")) for i in instructions)
    print(f"Total character length: {total_len}")
else:
    print("No system_instructions in v2.0 JSON.")

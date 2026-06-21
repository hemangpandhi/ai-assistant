import json

with open("app/src/main/assets/vehicle_skills_registry.json") as f:
    data = json.load(f)

for tool in data.get("tools", []):
    if tool.get("handler_key") == "checkRange":
        print("Found checkRange")
        print(tool)
        break
else:
    print("checkRange NOT found in OLD json!")

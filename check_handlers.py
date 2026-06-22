import json
import re
import os
import glob

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

# Find all CUSTOM_KOTLIN handlers in the registry
registry_handlers = set()
for tool in data["tools"]:
    if tool.get("handler_type") == "CUSTOM_KOTLIN":
        registry_handlers.add(tool.get("handler_key"))

# Find all implemented handlers in .kt files
implemented_handlers = set()
kt_files = glob.glob("app/src/main/java/com/example/gemininano/handlers/*.kt")
for file in kt_files:
    with open(file, "r") as f:
        content = f.read()
        # Find all strings inside quotes that are before a -> on a line containing ->
        lines = content.split('\n')
        for line in lines:
            if '->' in line and '"' in line:
                left_side = line.split('->')[0]
                matches = re.findall(r'"([^"]+)"', left_side)
                implemented_handlers.update(matches)

# Compare
missing_in_kotlin = registry_handlers - implemented_handlers

print("Handlers required by JSON but missing in Kotlin code:")
if not missing_in_kotlin:
    print("None!")
else:
    for h in missing_in_kotlin:
        print(h)

import re
import json

with open("use-cases.md", "r") as f:
    text = f.read()

# Find all <TOOL>...</TOOL> in use-cases.md
tools_in_md = set(re.findall(r'<TOOL>(.*?)\(.*?\)<\/TOOL>', text))

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

tools_in_registry = set()
for tool in data["tools"]:
    prompt = tool.get("prompt_string", "")
    match = re.search(r'<TOOL>(.*?)\(', prompt)
    if match:
        tools_in_registry.add(match.group(1))
    else:
        # Check without parameters
        match2 = re.search(r'<TOOL>(.*?)<\/TOOL>', prompt)
        if match2:
            tools_in_registry.add(match2.group(1))

print("Tools in use-cases.md NOT in registry:")
for t in tools_in_md:
    if t not in tools_in_registry:
        print(t)
        
print("---")

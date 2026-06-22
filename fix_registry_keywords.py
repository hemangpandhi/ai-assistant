import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

for tool in data["tools"]:
    prompt = tool.get("prompt_string", "")
    keywords = tool.get("keywords", [])
    
    # Check if this tool is a "turnOn" or "turnOff" tool
    if "turnOn" in prompt or "turnOff" in prompt:
        action = "turn on " if "turnOn" in prompt else "turn off "
        # Generate new distinct keywords
        new_keywords = set(keywords)
        for kw in keywords:
            if not kw.startswith("turn on ") and not kw.startswith("turn off ") and not kw.startswith("activate ") and not kw.startswith("deactivate "):
                new_keywords.add(action + kw)
        
        tool["keywords"] = list(new_keywords)

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

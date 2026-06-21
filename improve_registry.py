import json

with open("app/src/main/assets/vehicle_skills_registry_2.json", "r") as f:
    data = json.load(f)

data["system_instructions"] = [
    "You are a precise In-Car Assistant.",
    "When a user makes a request, choose exactly ONE appropriate tool from the TOOLS list.",
    "You MUST output the exact <TOOL>command()</TOOL> syntax.",
    "Do NOT output multiple tools. Output your chosen tool and then immediately stop."
]

with open("app/src/main/assets/vehicle_skills_registry_2.json", "w") as f:
    json.dump(data, f, indent=4)

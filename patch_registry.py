import json

path = "app/src/main/assets/vehicle_skills_registry.json"
with open(path, "r") as f:
    data = json.load(f)

# Tools that need vehicle state injection
state_tools = {
    "setTemperature", "increaseTemperature", "decreaseTemperature", 
    "setFanSpeed", "setSeatHeater", "setSeatMassager", "openWindow", 
    "closeWindow", "navigate", "controlHomeDevice"
}

for tool in data["tools"]:
    if tool.get("handler_key") in state_tools:
        tool["requires_vehicle_state"] = True

data["system_instructions"] = [
    {
        "instruction": "NAVIGATION: To navigate, briefly acknowledge the destination and then use the syntax <TOOL>navigate(DEST)</TOOL> at the end. Example: \"Setting destination to Tokyo. <TOOL>navigate(Tokyo)\"",
        "keywords": ["navigate", "home", "work", "directions", "drive"]
    },
    {
        "instruction": "SIGHTSEEING: If asked about a city, places to visit, or sightseeing, YOU MUST use your world knowledge to suggest places AND THEN YOU MUST END YOUR RESPONSE WITH THE EXACT QUESTION: \"Which places would you like to visit?\". Do NOT use the navigate tool until they answer!",
        "keywords": ["places", "visit", "sightseeing", "suggest", "city", "tokyo", "japan", "where to go"]
    },
    {
        "instruction": "AMBIGUITY: If the user replies with a specific place from your list, you MUST use the <TOOL>navigate(DEST)</TOOL> tool to navigate there.",
        "keywords": ["places", "visit", "sightseeing", "suggest", "city", "tokyo", "japan", "where to go"]
    }
]

with open(path, "w") as f:
    json.dump(data, f, indent=4)
print("Registry patched successfully.")

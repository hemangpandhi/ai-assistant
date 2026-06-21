import json

with open('app/src/main/assets/vehicle_skills_registry_v2.0.json', 'r') as f:
    data = json.load(f)

# Keyword expansions
expansions = {
    "increaseTemperature": ["heat", "heater", "warm", "turn up", "freezing", "cold"],
    "decreaseTemperature": ["heat", "turn down", "cool", "ac", "hot", "sweating"],
    "setTemperature": ["set heat", "climate control", "ac"],
    "navigate": ["drive to", "take me to", "route", "destination", "head to"],
    "call": ["phone", "talk to", "dial"],
    "callContact": ["phone", "talk to", "dial", "call"],
    "search": ["look up", "find", "gas station", "fuel", "where is", "hungry", "food"],
    "getBatteryLevel": ["charge", "range", "power"],
    "defogWindshield": ["fog", "can't see", "visibility", "defrost", "mist"],
    "improveRoadVisibility": ["fog", "can't see", "visibility", "dark"],
    "setWindowPosition": ["roll down", "roll up", "glass", "windows"],
    "setAllWindowsPosition": ["roll down", "roll up", "glass", "windows"],
    "openTrunk": ["boot", "back"],
    "playMusic": ["song", "spotify", "listen", "play"],
    "handleFeelingCold": ["freezing", "cold", "turn up heat", "shivering"],
    "setSeatHeater": ["back", "freezing", "cold", "warm seat"],
    "setSeatMassager": ["tired", "back hurts", "massage", "relax"],
    "suggestNearbyPlaces": ["visit", "around", "tourist", "sightseeing"],
    "prepareForIncomingRain": ["rain", "raining", "wet", "umbrella"]
}

for tool in data.get('tools', []):
    key = tool.get('handler_key')
    if key in expansions:
        existing = set(tool.get('keywords', []))
        existing.update(expansions[key])
        tool['keywords'] = list(existing)

with open('app/src/main/assets/vehicle_skills_registry_v2.0.json', 'w') as f:
    json.dump(data, f, indent=4)

print("Updated keywords successfully.")

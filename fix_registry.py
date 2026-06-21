import json

with open("old_registry.json", "r") as f:
    old_data = json.load(f)

old_tools = old_data.get("tools", [])

with open("app/src/main/assets/vehicle_skills_registry_2.json", "r") as f:
    new_data = json.load(f)

new_tools = new_data.get("tools", [])
new_tool_keys = [t.get("handler_key") for t in new_tools if "handler_key" in t]

for old_tool in old_tools:
    key = old_tool.get("handler_key")
    if key in ["navigate", "search", "searchNearby", "suggestNearbyPlaces", "provideLaneLevelGuidance", "suggestAlternateRoute", "getWeather", "playMusic", "pauseMusic", "nextTrack", "prevTrack", "callContact", "sendText", "remember"]:
        if key not in new_tool_keys:
            print(f"Adding {key} back to registry")
            new_tools.append(old_tool)

new_data["tools"] = new_tools

with open("app/src/main/assets/vehicle_skills_registry_2.json", "w") as f:
    json.dump(new_data, f, indent=4)

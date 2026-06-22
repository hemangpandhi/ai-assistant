import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

new_tools = []
for tool in data["tools"]:
    prompt = tool.get("prompt_string", "")
    
    # 1. DELETIONS
    if "call(NAME)" in prompt or "setAllWindowsPosition(PCT)" in prompt or "searchNearby(amenity)" in prompt:
        continue
        
    # 2. MODIFICATIONS
    if "search(search_term)" in prompt:
        tool["keywords"] = ["look up", "find", "where is", "suggest places", "search", "search for"]
    elif "startNavigationTo(" in prompt:
        tool["keywords"] = ["drive to", "navigate to", "route to", "directions", "take me to", "go to"]
    elif "getBatteryLevel()" in prompt:
        tool["keywords"] = ["battery level", "how much battery", "state of charge", "battery percentage"]
    elif "explainLowRange()" in prompt:
        tool["keywords"] = ["why is my range low", "range dropped", "low range", "battery drain"]
    elif "suggestOptimizedChargingRate()" in prompt:
        tool["keywords"] = ["optimize charging", "charge faster", "charging rate", "battery health"]
    elif "openWindowsSlightly()" in prompt:
        tool["keywords"] = ["open windows slightly", "crack the windows", "little bit open"]
    elif "closeAllWindows()" in prompt:
        tool["keywords"] = ["close windows", "shut all windows", "secure windows"]
    elif "setWindowPosition(PCT)" in prompt:
        tool["keywords"] = ["roll down window", "roll up window", "glass position", "window position"]
        
    new_tools.append(tool)

data["tools"] = new_tools

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

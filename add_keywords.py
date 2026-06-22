import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

for tool in data.get("tools", []):
    key = tool.get("handler_key") or tool.get("prompt_string")
    if "increaseTemperature" in key or "handleFeelingCold" in key:
        tool.setdefault("keywords", []).extend(["freezing", "cold"])
    if "suggestOptimizedChargingRate" in key or "explainLowRange" in key:
        tool.setdefault("keywords", []).extend(["battery", "charge", "range"])
    if "decreaseTemperature" in key:
        tool.setdefault("keywords", []).extend(["heat"])
    if "navigate" in key:
        tool.setdefault("keywords", []).extend(["navigate", "direction", "route"])
    if "call" in key:
        tool.setdefault("keywords", []).extend(["call", "phone", "dial"])
    if "playMusic" in key:
        tool.setdefault("keywords", []).extend(["play", "music", "song"])
    if "setWindowPosition" in key or "setDriverWindowPosition" in key or "setAllWindowsPosition" in key:
        tool.setdefault("keywords", []).extend(["open", "window"])

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

print("Keywords injected for 0ms Semantic Search bypass.")

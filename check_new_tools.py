import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

tools = data.get("tools", [])

def check_tool(key, keywords_to_add):
    for tool in tools:
        if tool.get("handler_key") == key or tool.get("prompt_string") == f"<TOOL>{key}()</TOOL>":
            kws = tool.setdefault("keywords", [])
            for kw in keywords_to_add:
                if kw not in kws:
                    kws.append(kw)
            return True
    return False

check_tool("defogWindshield", ["fog", "windshield", "fogged"])
check_tool("protectFromPollutedAir", ["polluted", "smog", "air quality", "smells"])
check_tool("handleDrowsyDriving", ["sleepy", "drowsy", "tired"])
check_tool("prepareForAirportTrip", ["airport", "flight", "terminal"])
check_tool("turnOffAC", ["turn off", "ac", "air conditioner"])
check_tool("setAllWindowsPosition", ["open", "all windows"])
check_tool("openTrunk", ["trunk", "boot"])

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

print("Keywords for new test cases injected!")

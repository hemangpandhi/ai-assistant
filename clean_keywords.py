import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

for tool in data["tools"]:
    prompt = tool.get("prompt_string", "")
    
    # AC Power
    if "turnOnAC" in prompt:
        tool["keywords"] = ["turn on ac", "start ac", "activate ac", "ac on", "turn on air conditioning"]
    elif "turnOffAC" in prompt:
        tool["keywords"] = ["turn off ac", "stop ac", "deactivate ac", "ac off", "turn off air conditioning"]
        
    # HVAC Power
    elif "turnOnHvacPower" in prompt:
        tool["keywords"] = ["turn on hvac", "start climate control", "power on climate", "turn on climate"]
    elif "turnOffHvacPower" in prompt:
        tool["keywords"] = ["turn off hvac", "stop climate control", "power off climate", "turn off climate"]
        
    # Fan Speed (remove 'ac' and 'air' to prevent bleed)
    elif "setFanSpeed" in prompt:
        tool["keywords"] = ["fan", "speed", "blower", "set fan"]
    elif "increaseFanSpeed" in prompt:
        tool["keywords"] = ["increase fan", "fan up", "blow harder", "more fan", "higher fan"]
    elif "decreaseFanSpeed" in prompt:
        tool["keywords"] = ["decrease fan", "fan down", "blow softer", "less fan", "lower fan"]
        
    # Temperature (remove 'ac' to prevent bleed)
    elif "setTemperature" in prompt:
        tool["keywords"] = ["temperature", "degrees", "hotter", "colder", "warmer", "cooler", "set temp"]
    elif "increaseTemperature" in prompt:
        tool["keywords"] = ["increase temperature", "warmer", "hotter", "raise temp"]
    elif "decreaseTemperature" in prompt:
        tool["keywords"] = ["decrease temperature", "cooler", "colder", "lower temp"]

    # Defroster
    elif "turnOnFrontDefroster" in prompt:
        tool["keywords"] = ["turn on front defroster", "defrost front windshield", "clear front window"]
    elif "turnOffFrontDefroster" in prompt:
        tool["keywords"] = ["turn off front defroster", "stop defrosting front", "front defroster off"]
    elif "turnOnRearDefroster" in prompt:
        tool["keywords"] = ["turn on rear defroster", "defrost rear window", "clear back window"]
    elif "turnOffRearDefroster" in prompt:
        tool["keywords"] = ["turn off rear defroster", "stop defrosting rear", "rear defroster off"]

    # Recirculation
    elif "turnOnRecirculation" in prompt:
        tool["keywords"] = ["turn on recirculation", "recirculate air", "inside air only", "block outside air"]
    elif "turnOffRecirculation" in prompt:
        tool["keywords"] = ["turn off recirculation", "fresh air", "allow outside air", "stop recirculating"]

    # Cabin Lights
    elif "turnOnCabinLight" in prompt:
        tool["keywords"] = ["turn on cabin lights", "turn on interior lights", "lights on inside"]
    elif "turnOffCabinLight" in prompt:
        tool["keywords"] = ["turn off cabin lights", "turn off interior lights", "lights off inside"]

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "w") as f:
    json.dump(data, f, indent=4)

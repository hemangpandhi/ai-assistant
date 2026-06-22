import re
import json

filepath = "app/src/main/assets/vehicle_skills_registry_v2.0.json"

with open(filepath, 'r') as f:
    content = f.read()

# Remove all // comments
content = re.sub(r'^\s*//.*$', '', content, flags=re.MULTILINE)

# Fix missing commas between objects in array
# Find `}` followed by whitespace and `{` and insert `,`
content = re.sub(r'}\s*{', '},\n{', content)

# Try to parse
try:
    data = json.loads(content)
except Exception as e:
    print(f"Failed to parse JSON after basic cleanup: {e}")
    # Let's write the intermediate file out to debug if it fails
    with open("debug_v2.json", 'w') as f:
        f.write(content)
    exit(1)

# Add error_message to tools
for tool in data.get("tools", []):
    if tool.get("handler_type") == "GENERIC_VHAL_WRITE" or tool.get("handler_key") in ["increaseTemperature", "decreaseTemperature", "setTemperature"]:
        if "error_message" not in tool:
            tool["error_message"] = "The vehicle hardware did not confirm the change."

with open(filepath, 'w') as f:
    json.dump(data, f, indent=4)

print("Successfully cleaned, updated, and saved v2.0 JSON.")

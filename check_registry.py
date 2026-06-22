import json
import re

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

json_handlers = set()
for tool in data["tools"]:
    if tool.get("handler_type") == "CUSTOM_KOTLIN":
        json_handlers.add(tool.get("handler_key"))

registry_handlers = set()
with open("app/src/main/java/com/example/gemininano/handlers/ToolHandlerRegistry.kt", "r") as f:
    content = f.read()
    matches = re.findall(r'"([^"]+)"', content)
    registry_handlers.update(matches)

missing = json_handlers - registry_handlers
print("Handlers in JSON but missing in ToolHandlerRegistry.kt:")
for m in missing:
    print(m)

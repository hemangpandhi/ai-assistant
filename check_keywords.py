import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

tools = data.get("tools", [])
empty_keywords = [t.get("handler_key", t.get("prompt_string")) for t in tools if not t.get("keywords")]
print(f"Tools with empty keywords: {len(empty_keywords)}")
if empty_keywords:
    print(empty_keywords[:10])


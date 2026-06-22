import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

query = "i am feeling cold"
tools = data.get("tools", [])

matches = []
for tool in tools:
    keywords = tool.get("keywords", [])
    # ToolManager.kt does: tool.keywords?.any { q.contains(it) } == true
    if any(k in query for k in keywords):
        matches.append(tool.get("handler_key") or tool.get("prompt_string"))

print(f"Query: '{query}' -> Matches: {matches}")

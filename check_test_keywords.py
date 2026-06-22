import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

test_queries = [
    "I'm freezing",
    "Turn down the heat",
    "Navigate to San Francisco",
    "Call my mechanic",
    "Play some relaxing music",
    "Open the driver window",
    "How much battery is left?"
]

tools = data.get("tools", [])

for query in test_queries:
    q = query.lower()
    matches = [t for t in tools if any(k in q for k in t.get("keywords", []))]
    print(f"Query: '{query}' -> Keyword matches: {len(matches)}")


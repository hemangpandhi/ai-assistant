import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json") as f:
    data = json.load(f)

tools = data.get("tools", [])

print(f"Total tools: {len(tools)}")
# Simulating getLlmToolsPrompt which prints promptString for relevant tools.
# Let's see how long promptString is for all tools
total_length = sum(len(t.get("prompt_string", "")) for t in tools)
print(f"Total prompt length if ALL tools were included: {total_length} characters")


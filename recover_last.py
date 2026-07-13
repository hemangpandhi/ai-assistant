import json
import os

transcript_file = "/home/tcs/.gemini/antigravity/brain/75fb1791-55cb-4c64-ba90-db652cc2add1/.system_generated/logs/transcript_full.jsonl"
file_contents = {}

for line in open(transcript_file):
    try:
        data = json.loads(line)
        if data.get("type") == "PLANNER_RESPONSE":
            tool_calls = data.get("tool_calls", [])
            for call in tool_calls:
                if call.get("name") == "write_to_file":
                    args = call.get("args", {})
                    target = args.get("TargetFile", "")
                    content = args.get("CodeContent", "")
                    if target.startswith("/home/tcs/AI_Assistant/ai-sample/") and ("CockpitAwarenessActivity.kt" in target or "activity_cockpit_awareness.xml" in target):
                        file_contents[target] = content
    except Exception as e:
        pass

for target, content in file_contents.items():
    print(f"Restoring {target}")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w") as f:
        f.write(content)

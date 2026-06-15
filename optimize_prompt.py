import re

with open("app/src/main/java/com/example/gemininano/LLMManager.kt", "r") as f:
    content = f.read()

# Remove Example 1 through Example 5
content = re.sub(r'        if \(isHvac \|\| q\.isEmpty\(\)\) \{\n.*?Example 5:.*?\n        \}\n', '', content, flags=re.DOTALL)

# Remove Example 6 through Example 10
content = re.sub(r'        if \(isSightseeing \|\| q\.isEmpty\(\)\) \{\n.*?Example 10:.*?\n        \}\n', 
                 '        if (isSightseeing || q.isEmpty()) {\n            basePrompt.append("Example: If asked to show places on map, output <TOOL>search(QUERY)</TOOL>.\\n\\n")\n        }\n', 
                 content, flags=re.DOTALL)

# Remove Contextual Diagnostics & Servicing examples
content = re.sub(r'        if \(isDiag \|\| q\.isEmpty\(\)\) \{\n.*?\[Door Alert Check\].*?\n        \}\n', '', content, flags=re.DOTALL)

with open("app/src/main/java/com/example/gemininano/LLMManager.kt", "w") as f:
    f.write(content)

print("Prompt optimized!")

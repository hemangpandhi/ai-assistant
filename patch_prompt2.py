import re

path = "app/src/main/java/com/example/gemininano/LLMManager.kt"
with open(path, "r") as f:
    content = f.read()

# Fix the prompt instructions to include the example, and make VehicleContext conditional
old_prompt = """        // Universal Agentic Context Injection
        basePrompt.append("=== VEHICLE STATE ===\\\\n")
        basePrompt.append("${VehicleManager.getLLMContextString(context)}\\\\n\\\\n")
        
        basePrompt.append("IMPORTANT: If you use a tool, YOU MUST ALWAYS say what you are doing FIRST, and then append the XML TAG '<TOOL>' at the very end of your response.\\\\n\\\\n")"""

new_prompt = """        // Universal Agentic Context Injection
        val isComplex = query.lowercase().let { it.contains("temperature") || it.contains("navigate") || it.contains("home") || it.contains("window") }
        if (isComplex) {
            basePrompt.append("=== VEHICLE STATE ===\\\\n")
            basePrompt.append("${VehicleManager.getLLMContextString(context)}\\\\n\\\\n")
        }
        
        basePrompt.append("IMPORTANT: If you use a tool, YOU MUST ALWAYS say what you are doing FIRST, and then append the XML TAG '<TOOL>' at the very end of your response. Example: 'Playing relaxing music now. <TOOL>playMusic(relaxing music)</TOOL>'\\\\n\\\\n")"""

if old_prompt in content:
    content = content.replace(old_prompt, new_prompt)
    with open(path, "w") as f:
        f.write(content)
    print("Patched LLMManager successfully.")
else:
    print("Could not find old_prompt string.")

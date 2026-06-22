path = "app/src/main/java/com/example/gemininano/LLMManager.kt"
with open(path, "r") as f:
    content = f.read()

old_logic = """        // Universal Agentic Context Injection
        val isComplex = query.lowercase().let { it.contains("temperature") || it.contains("navigate") || it.contains("home") || it.contains("window") }
        if (isComplex) {
            basePrompt.append("=== VEHICLE STATE ===\\\\n")
            basePrompt.append("${VehicleManager.getLLMContextString(context)}\\\\n\\\\n")
        }"""

new_logic = """        // Universal Agentic Context Injection
        val isComplex = query.lowercase().let { it.contains("temperature") || it.contains("navigate") || it.contains("home") || it.contains("window") || it.contains("places") || it.contains("visit") || it.contains("suggest") }
        if (isComplex) {
            basePrompt.append("=== VEHICLE STATE ===\\\\n")
            basePrompt.append("${VehicleManager.getLLMContextString(context)}\\\\n\\\\n")
            basePrompt.append("=== STRICT RULES ===\\\\n")
            basePrompt.append("1. NAVIGATION: To navigate, briefly acknowledge the destination and then use the syntax <TOOL>navigate(DEST)</TOOL> at the end. Example: \\\"Setting destination to Tokyo. <TOOL>navigate(Tokyo)</TOOL>\\\"\\\\n")
            basePrompt.append("2. SIGHTSEEING: If asked about a city, places to visit, or sightseeing, YOU MUST use your world knowledge to suggest places AND THEN YOU MUST END YOUR RESPONSE WITH THE EXACT QUESTION: \\\"Which places would you like to visit?\\\". Do NOT use the navigate tool until they answer!\\\\n")
            basePrompt.append("3. AMBIGUITY: If the user replies with a specific place from your list, you MUST use the <TOOL>navigate(DEST)</TOOL> tool to navigate there.\\\\n\\\\n")
        }"""

if old_logic in content:
    content = content.replace(old_logic, new_logic)
    with open(path, "w") as f:
        f.write(content)
    print("Patched LLMManager with strict rules successfully.")
else:
    print("Could not find old_logic string.")

import re

path = "app/src/main/java/com/example/gemininano/ToolManager.kt"
with open(path, "r") as f:
    content = f.read()

# Replace getLlmToolsPrompt to exactly match optimization branch's conciseness
old_prompt = """    fun getLlmToolsPrompt(context: Context, query: String = "", previousExecutedTools: Set<String> = emptySet()): String {
        val relevantTools = getRelevantTools(context, query, previousExecutedTools)
        if (relevantTools.isEmpty()) return ""
        
        val builder = StringBuilder("AVAILABLE TOOLS (Choose EXACTLY ONE tool. DO NOT output a list):\\\\n")
        var constraintIndex = 1
        relevantTools.forEach { tool ->
            builder.append("${tool.promptString}\\\\n")
            if (tool.propertyId != null && VehicleManager.customPropertyIdToInstruction.containsKey(tool.propertyId)) {
                builder.append("Constraint $constraintIndex: ${VehicleManager.customPropertyIdToInstruction[tool.propertyId]}\\\\n")
                constraintIndex++
            }
        }
        return builder.toString().trim()
    }"""

new_prompt = """    fun getLlmToolsPrompt(context: Context, query: String = "", previousExecutedTools: Set<String> = emptySet()): String {
        val relevantTools = getRelevantTools(context, query, previousExecutedTools)
        if (relevantTools.isEmpty()) return ""
        return relevantTools.map { it.promptString }.joinToString("\\n")
    }"""

if old_prompt in content:
    content = content.replace(old_prompt, new_prompt)
    with open(path, "w") as f:
        f.write(content)
    print("Patched getLlmToolsPrompt successfully.")
else:
    print("Could not find old_prompt string.")

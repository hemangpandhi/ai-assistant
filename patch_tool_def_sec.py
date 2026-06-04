import re

with open("app/src/main/java/com/example/gemininano/ToolManager.kt", "r") as f:
    code = f.read()

old_def = """        val successMessage: String?,
        val keywords: List<String>?,
        val constraints: List<Constraint>?
    )"""

new_def = """        val successMessage: String?,
        val keywords: List<String>?,
        val constraints: List<Constraint>?,
        val requiresConfirmation: Boolean = false,
        val confirmationMessage: String? = null
    )"""

code = code.replace(old_def, new_def)

old_parse = """                        if (constraintsList.isNotEmpty()) constraintsList else null
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")"""

new_parse = """                        if (constraintsList.isNotEmpty()) constraintsList else null,
                        requiresConfirmation = if (toolObj.has("requires_confirmation")) toolObj.getBoolean("requires_confirmation") else false,
                        confirmationMessage = if (toolObj.has("confirmation_message")) toolObj.getString("confirmation_message") else null
                    )
                    Log.i(TAG, "Registered Tool: $commandName ($handlerType) -> $promptString")"""

code = code.replace(old_parse, new_parse)

getter = """
    fun getToolDefinition(toolCall: String): ToolDefinition? {
        val commandName = toolCall.substringBefore("(").trim()
        return activeTools[commandName]
    }
"""
code = code.replace("    fun getLlmToolsPrompt(query: String = \"\"): String {", getter + "    fun getLlmToolsPrompt(query: String = \"\"): String {")

with open("app/src/main/java/com/example/gemininano/ToolManager.kt", "w") as f:
    f.write(code)

print("ToolManager.kt updated for security.")

import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    code = f.read()

# 1. Add pendingConfirmationTool instance variable
code = code.replace("private var dotAnimatorJob: kotlinx.coroutines.Job? = null", "private var dotAnimatorJob: kotlinx.coroutines.Job? = null\n    private var pendingConfirmationTool: String? = null")

# 2. Add Native User Validation in executeCommand
old_final_prompt = """        val finalPrompt: String
        if (LLMManager.isFirstMessage) {"""

new_final_prompt = """        var interceptedQuery = query
        
        // Security: Native User Validation
        if (pendingConfirmationTool != null) {
            val q = query.lowercase()
            if (q.contains("yes") || q.contains("yeah") || q.contains("sure") || q.contains("do it") || q.contains("ok")) {
                val toolToExecute = pendingConfirmationTool!!
                pendingConfirmationTool = null
                val feedback = kotlinx.coroutines.runBlocking { executeToolCall(toolToExecute) }
                interceptedQuery = "System: Executed $toolToExecute. Result: $feedback. User originally said 'yes'."
            } else {
                pendingConfirmationTool = null
                interceptedQuery = "System: Action aborted by user. User originally said: $query"
            }
        }
        
        val finalPrompt: String
        if (LLMManager.isFirstMessage) {"""

code = code.replace(old_final_prompt, new_final_prompt)
code = code.replace("val sysPrompt = LLMManager.getSystemPrompt(context, query)", "val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)")
code = code.replace("finalPrompt = \"$sysPrompt\\n\\nUser: $query\"", "finalPrompt = \"$sysPrompt\\n\\nUser: $interceptedQuery\"")
code = code.replace("finalPrompt = \"[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder\\nUser: $query\"", "finalPrompt = \"[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder\\nUser: $interceptedQuery\"")

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(code)

print("AssistantSession Native User Validation patched.")

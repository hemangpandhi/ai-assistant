import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    code = f.read()

# 1. Update finalPrompt logic to include Sliding Window Context
old_final = """        val finalPrompt: String
        if (LLMManager.isFirstMessage) {
            val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
            finalPrompt = "$sysPrompt\\n\\nUser: $interceptedQuery"
            LLMManager.isFirstMessage = false
        } else {
            val reminder = "\\n(Reminder: Use exact <TOOL> XML tags for car actions.)"
            finalPrompt = "[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder\\nUser: $interceptedQuery"
        }"""

new_final = """        MemoryManager.addTurn("User", interceptedQuery)
        val slidingHistory = MemoryManager.getSlidingWindowContext(3000)
        
        val finalPrompt: String
        if (LLMManager.isFirstMessage || slidingHistory.isNotEmpty()) {
            val sysPrompt = LLMManager.getSystemPrompt(context, interceptedQuery)
            val reminder = "\\n(Reminder: Use exact <TOOL> XML tags for car actions.)"
            
            // If we have sliding history, we must be recovering from a KV Cache wipe or it's a new turn after a wipe.
            if (slidingHistory.isNotEmpty() && !LocalLLMActivity.isCloudModelActive) {
                finalPrompt = "$sysPrompt\\n$reminder\\n\\n[Conversation History]\\n$slidingHistory\\nAssistant:"
            } else {
                finalPrompt = "$sysPrompt\\n$reminder\\n\\nUser: $interceptedQuery"
            }
            LLMManager.isFirstMessage = false
        } else {
            val reminder = "\\n(Reminder: Use exact <TOOL> XML tags for car actions.)"
            finalPrompt = "[Current State: ${VehicleManager.getLLMContextString(context)}]$reminder\\nUser: $interceptedQuery"
        }"""
        
code = code.replace(old_final, new_final)

# 2. Add Assistant Turn to Memory & Handle KV Cache Overflow gracefully
old_hack = """                            // Auto-Context Clearing Hack for silent KV Cache overflows
                            if (finalMsg.trim().length <= 3) {
                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Resetting...")
                                LLMManager.resetConversation()
                                handleQuery(query)
                                return@launch
                            }"""

new_hack = """                            // Auto-Context Clearing Hack for silent KV Cache overflows
                            if (finalMsg.trim().length <= 3) {
                                android.util.Log.w("AssistantSession", "Suspiciously short response. KV Cache full. Graceful Sliding Window Reset initiated...")
                                LLMManager.resetConversation()
                                // The query has already been added to MemoryManager as User turn, so we just retry.
                                // The next handleQuery will pull the SlidingWindowContext automatically!
                                handleQuery(query)
                                return@launch
                            }
                            
                            MemoryManager.addTurn("Assistant", finalMsg.trim())"""

code = code.replace(old_hack, new_hack)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(code)

print("AssistantSession.kt patched for MemoryManager.")

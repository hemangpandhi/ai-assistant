import re

with open("app/src/main/java/com/example/gemininano/MemoryManager.kt", "r") as f:
    code = f.read()

gemini_method = """    fun getAnthropicHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "assistant"
                put("role", apiRole)
                put("content", turn.content)
            }
        }
    }
    
    fun getGeminiHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "model"
                put("role", apiRole)
                put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", turn.content)))
            }
        }
    }"""

code = code.replace("""    fun getAnthropicHistory(): List<org.json.JSONObject> {
        return conversationHistory.map { turn ->
            org.json.JSONObject().apply {
                val apiRole = if (turn.role.equals("User", true)) "user" else "assistant"
                put("role", apiRole)
                put("content", turn.content)
            }
        }
    }""", gemini_method)

with open("app/src/main/java/com/example/gemininano/MemoryManager.kt", "w") as f:
    f.write(code)

with open("app/src/main/java/com/example/gemininano/GeminiManager.kt", "r") as f:
    code = f.read()

code = code.replace("            conversationHistory.add(JSONObject().apply {\n                put(\"role\", \"user\")\n                put(\"parts\", JSONArray().put(JSONObject().put(\"text\", userMessage)))\n            })", "")
code = code.replace("                put(\"contents\", JSONArray(conversationHistory))", "                put(\"contents\", JSONArray(MemoryManager.getGeminiHistory()))")
code = code.replace("            conversationHistory.add(JSONObject().apply {\n                put(\"role\", \"model\")\n                put(\"parts\", JSONArray().put(JSONObject().put(\"text\", assistantText)))\n            })", "")

with open("app/src/main/java/com/example/gemininano/GeminiManager.kt", "w") as f:
    f.write(code)

print("Gemini history patched.")

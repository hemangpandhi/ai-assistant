import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

# 1. Fix TTS onError
old_tts = """                override fun onError(utteranceId: String?) {
                    CoroutineScope(Dispatchers.Main).launch {
                        finish()
                    }
                }"""
new_tts = """                override fun onError(utteranceId: String?) {
                    android.util.Log.e("AssistantSession", "TTS Error: " + utteranceId)
                }"""
content = content.replace(old_tts, new_tts)

# 2. Fix handleQuery to launch Coroutine
old_handle = """        processQuery(query)
    }
    
    private fun processQuery(query: String) {"""
new_handle = """        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            processQuery(query)
        }
    }
    
    private suspend fun processQuery(query: String) {"""
content = content.replace(old_handle, new_handle)

# 3. Fix runBlocking
old_runblocking = "val feedback = kotlinx.coroutines.runBlocking { executeToolCall(toolToExecute) }"
new_runblocking = "val feedback = executeToolCall(toolToExecute)"
content = content.replace(old_runblocking, new_runblocking)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)

print("ANR and TTS fixes applied.")

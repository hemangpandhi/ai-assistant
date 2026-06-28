import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/LLMManager.kt"
with open(file_path, "r") as f:
    text = f.read()

# 1. Volatile annotations
text = text.replace("var isInitializing = false", "@Volatile var isInitializing = false")
text = text.replace("var activeBackendString = \"Unknown\"", "@Volatile var activeBackendString = \"Unknown\"")
text = text.replace("var isPrewarming = false", "@Volatile var isPrewarming = false")

# 2. Add runBlocking import
if "import kotlinx.coroutines.runBlocking" not in text:
    text = text.replace("import kotlinx.coroutines.launch", "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.runBlocking")

# Unload method thread locking
old_unload = """    fun unload() {
        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            Log.w("LLMManager", "Failed to cleanly close inference instance during unload.", e)
        } finally {
            conversation = null
            engine = null
            isFirstMessage = true
            System.gc()
            Log.i("LLMManager", "LLM Model unloaded from memory to save resources.")
        }
    }"""
new_unload = """    fun unload() = runBlocking {
        initMutex.withLock {
            try {
                conversation?.close()
                engine?.close()
            } catch (e: Exception) {
                Log.w("LLMManager", "Failed to cleanly close inference instance during unload.", e)
            } finally {
                conversation = null
                engine = null
                isFirstMessage = true
                System.gc()
                Log.i("LLMManager", "LLM Model unloaded from memory to save resources.")
            }
        }
    }"""
text = text.replace(old_unload, new_unload)

# 3. posix_fadvise
old_engine = """                val engineConfig = EngineConfig(
                    modelPath = modelPath,"""
new_engine = """                try {
                    val fd = java.io.FileInputStream(modelPath).fd
                    val length = java.io.File(modelPath).length()
                    android.system.Os.posix_fadvise(fd, 0, length, android.system.OsConstants.POSIX_FADV_SEQUENTIAL)
                    Log.i("LLMManager", "Applied posix_fadvise POSIX_FADV_SEQUENTIAL to optimize memory caching for: $modelPath")
                } catch (e: Exception) {
                    Log.w("LLMManager", "posix_fadvise optimization failed: ${e.message}")
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,"""
text = text.replace(old_engine, new_engine)

with open(file_path, "w") as f:
    f.write(text)

import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/LLMManager.kt"
with open(file_path, "r") as f:
    text = f.read()

old_engine = """                try {
                    val fd = java.io.FileInputStream(modelPath).fd
                    val length = java.io.File(modelPath).length()
                    android.system.Os.posix_fadvise(fd, 0, length, android.system.OsConstants.POSIX_FADV_SEQUENTIAL)
                    Log.i("LLMManager", "Applied posix_fadvise POSIX_FADV_SEQUENTIAL to optimize memory caching for: $modelPath")
                } catch (e: Exception) {
                    Log.w("LLMManager", "posix_fadvise optimization failed: ${e.message}")
                }"""

# Using reflection
new_engine = """                try {
                    val fd = java.io.FileInputStream(modelPath).fd
                    val length = java.io.File(modelPath).length()
                    // Android hides posix_fadvise from the public SDK, so we access it via reflection for the kernel hint
                    val osClass = Class.forName("android.system.Os")
                    val posixFadvise = osClass.getMethod("posix_fadvise", java.io.FileDescriptor::class.java, Long::class.java, Long::class.java, Int::class.java)
                    val osConstantsClass = Class.forName("android.system.OsConstants")
                    val sequentialFlag = osConstantsClass.getField("POSIX_FADV_SEQUENTIAL").getInt(null)
                    
                    posixFadvise.invoke(null, fd, 0L, length, sequentialFlag)
                    Log.i("LLMManager", "Applied posix_fadvise POSIX_FADV_SEQUENTIAL via reflection to optimize memory caching for: $modelPath")
                } catch (e: Exception) {
                    Log.w("LLMManager", "posix_fadvise optimization failed or is unsupported on this kernel: ${e.message}")
                }"""

text = text.replace(old_engine, new_engine)

with open(file_path, "w") as f:
    f.write(text)

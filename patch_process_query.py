import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    lines = f.readlines()

new_lines = []
in_process_query = False
for idx, line in enumerate(lines):
    if "private fun processQuery(query: String) {" in line:
        in_process_query = True
        new_lines.append(line)
        continue
        
    if in_process_query:
        if "val prefs = context.getSharedPreferences(" in line:
            new_lines.append("        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {\n")
            new_lines.append("    " + line)
        elif "val feedback = kotlinx.coroutines.runBlocking { executeToolCall(toolToExecute) }" in line:
            new_lines.append(line.replace("kotlinx.coroutines.runBlocking { executeToolCall(toolToExecute) }", "executeToolCall(toolToExecute)"))
        elif "        } catch (e: Exception) {     } catch (e: Exception) {" in line:
            new_lines.append("        } catch (e: Exception) {\n")
        elif "    private fun startDotAnimation" in line:
            # End of processQuery
            new_lines.append("        }\n") # close the coroutine launch
            new_lines.append("    }\n\n")
            new_lines.append(line)
            in_process_query = False
        else:
            if "val prefs = context.getSharedPreferences(" not in "".join(new_lines[-10:]):
                new_lines.append(line)
            else:
                new_lines.append("    " + line)
    else:
        new_lines.append(line)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.writelines(new_lines)

print("Patched processQuery")

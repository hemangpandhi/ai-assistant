import re

filepath = "app/src/main/java/com/example/gemininano/handlers/HVACToolHandler.kt"
with open(filepath, 'r') as f:
    content = f.read()

# Fix interface
content = content.replace("): String {", "): ToolExecutionResult {")

# Restore Math.abs for decreaseTemperature
content = content.replace(
    'val value = Regex("-?\\\\d+(\\\\.\\\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0',
    'val value = Math.abs(Regex("-?\\\\d+(\\\\.\\\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)'
)

# Restore Math.abs for decreaseFanSpeed
content = content.replace(
    'val value = Regex("\\\\d+").find(argStr)?.value?.toIntOrNull() ?: 1',
    'val value = Math.abs(Regex("\\\\d+").find(argStr)?.value?.toIntOrNull() ?: 1)'
)

# In decreaseFanSpeed, it's Regex("\\d+") so Math.abs doesn't matter as much, but let's just make sure.

# Replace if (success) "..." else "..." with ToolExecutionResult
def replace_success(match):
    success_str = match.group(1).strip()
    fail_str = match.group(2).strip()
    return f'if (success) ToolExecutionResult(true, {success_str}) else ToolExecutionResult(false, {fail_str})'

content = re.sub(r'if\s*\(success\)\s*(".*?")\s*else\s*(".*?")', replace_success, content)
content = re.sub(r'else\s*->\s*(".*?")', lambda m: f'else -> ToolExecutionResult(false, {m.group(1)})', content)

with open(filepath, 'w') as f:
    f.write(content)

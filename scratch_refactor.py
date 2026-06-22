import os
import re

directory = "/home/hemang/socllm/AOSP_GeminiNano_Sample/app/src/main/java/com/example/gemininano/handlers"

def refactor_file(filepath):
    with open(filepath, 'r') as f:
        lines = f.readlines()
        
    out_lines = []
    for line in lines:
        if line.strip().startswith('if (success) matchedTool.successMessage ?: "') and '" else "' in line:
            # Match HVACToolHandler special case
            # if (success) matchedTool.successMessage ?: "I've increased the temperature by $value degrees." else "I sent the command, but the vehicle hardware didn't confirm the change."
            parts = line.split('else')
            left = parts[0].replace('if (success) matchedTool.successMessage ?: ', '')
            right = parts[1].strip()
            
            new_line = line[:line.find('if (success)')] + f"if (success) ToolExecutionResult(true, {left.strip()}) else ToolExecutionResult(false, {right})\n"
            out_lines.append(new_line)
        elif 'if (success) "' in line and '" else "' in line:
            # Generic if (success) "foo" else "bar"
            parts = line.split('else')
            left = parts[0].replace('if (success)', '').strip()
            right = parts[1].strip()
            new_line = line[:line.find('if (success)')] + f"if (success) ToolExecutionResult(true, {left}) else ToolExecutionResult(false, {right})\n"
            out_lines.append(new_line)
        elif line.strip().startswith('return "') or (line.strip().startswith('"') and line.strip().endswith('"') and 'println' not in line and 'Log.' not in line and '==' not in line):
            # standalone string returns
            stripped = line.strip()
            # if it starts with return, remove return
            if stripped.startswith('return '):
                val = stripped[7:]
            else:
                val = stripped
                
            if 'System Error' in val or 'couldn\'t' in val or 'Failed' in val or 'not recognized' in val or 'Safety Error' in val or 'Warning' in val:
                out_lines.append(line.replace(val, f"ToolExecutionResult(false, {val})"))
            else:
                out_lines.append(line.replace(val, f"ToolExecutionResult(true, {val})"))
        elif line.strip().startswith('matchedTool.successMessage ?: "'):
            # HVAC specific fallback
            val = line.strip().replace('matchedTool.successMessage ?: ', '')
            out_lines.append(line.replace(line.strip(), f"ToolExecutionResult(true, {val})"))
        else:
            out_lines.append(line)

    with open(filepath, 'w') as f:
        f.writelines(out_lines)

for filename in os.listdir(directory):
    if filename.endswith(".kt") and filename != "ToolExecutionResult.kt" and filename != "ToolHandler.kt" and filename != "ToolHandlerRegistry.kt":
        refactor_file(os.path.join(directory, filename))

import os
import glob
import re

handlers = glob.glob("app/src/main/java/com/example/gemininano/handlers/*Handler.kt")

for h_path in handlers:
    if "ToolHandler.kt" in h_path or "ToolHandlerRegistry.kt" in h_path:
        continue
        
    with open(h_path, 'r') as f:
        content = f.read()
        
    # More robust regex replacement for the signature
    content = re.sub(
        r'override suspend fun execute\(\s*context:\s*Context,\s*toolCall:\s*String,\s*intentHandler:\s*\(\(Intent\) -> Unit\)\?\s*\):\s*ToolExecutionResult',
        'override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult',
        content
    )

    with open(h_path, 'w') as f:
        f.write(content)


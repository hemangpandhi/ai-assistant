import glob

handlers = glob.glob("app/src/main/java/com/example/gemininano/handlers/*Handler.kt")

for h_path in handlers:
    if "ToolHandler.kt" in h_path or "ToolHandlerRegistry.kt" in h_path:
        continue
        
    with open(h_path, 'r') as f:
        content = f.read()
        
    # Replace the signature literally
    content = content.replace(
        'override suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult',
        'override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult'
    )

    with open(h_path, 'w') as f:
        f.write(content)


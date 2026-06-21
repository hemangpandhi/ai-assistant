import os
import glob
import re

filepath = "app/src/main/java/com/example/gemininano/handlers/ToolHandler.kt"
with open(filepath, 'r') as f:
    content = f.read()

content = content.replace(
    'suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult',
    'suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult'
)
with open(filepath, 'w') as f:
    f.write(content)

handlers = glob.glob("app/src/main/java/com/example/gemininano/handlers/*Handler.kt")

for h_path in handlers:
    if "ToolHandler.kt" in h_path or "ToolHandlerRegistry.kt" in h_path:
        continue
        
    with open(h_path, 'r') as f:
        content = f.read()
        
    # Update signature
    content = content.replace(
        'override suspend fun execute(context: Context, toolCall: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult',
        'override suspend fun execute(context: Context, toolCall: String, args: String, intentHandler: ((Intent) -> Unit)?): ToolExecutionResult'
    )
    
    # Remove all "val argStr = toolCall.substringAfter("(").substringBefore(")")"
    content = re.sub(r'val argStr = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)\n?', '', content)
    content = re.sub(r'val argStr = toolCall\.substringAfter\("\("\)\.substringBeforeLast\("\)"\)\n?', '', content)
    content = re.sub(r'val dest = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)\n?', 'val dest = args\n', content)
    content = re.sub(r'val amenity = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val amenity = args', content)
    content = re.sub(r'val query = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val query = args', content)
    content = re.sub(r'val contact = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val contact = args', content)
    content = re.sub(r'val searchTerm = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val searchTerm = args', content)
    content = re.sub(r'val fact = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val fact = args', content)
    content = re.sub(r'val city = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val city = args', content)
    content = re.sub(r'val appName = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val appName = args', content)
    content = re.sub(r'val valueStr = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val valueStr = args', content)
    
    # Inline arguments replacement where toolCall.substringAfter... was used directly
    content = content.replace('toolCall.substringAfter("(").substringBefore(")")', 'args')
    content = content.replace('toolCall.substringAfter("(").substringBeforeLast(")")', 'args')

    # Replace manual regex with ParameterParser
    content = content.replace('Math.abs(Regex("-?\\\\d+(\\\\.\\\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)', 'ParameterParser.extractDouble(args, 2.0)')
    content = content.replace('Math.abs(Regex("-?\\\\d+(\\\\.\\\\d+)?").find(args)?.value?.toDoubleOrNull() ?: 2.0)', 'ParameterParser.extractDouble(args, 2.0)')
    content = content.replace('Math.abs(Regex("\\\\d+").find(argStr)?.value?.toIntOrNull() ?: 1)', 'ParameterParser.extractInt(args, 1)')
    content = content.replace('Math.abs(Regex("\\\\d+").find(args)?.value?.toIntOrNull() ?: 1)', 'ParameterParser.extractInt(args, 1)')

    with open(h_path, 'w') as f:
        f.write(content)


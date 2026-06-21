import glob
import re

handlers = glob.glob("app/src/main/java/com/example/gemininano/handlers/*Handler.kt")

for h_path in handlers:
    if "ToolHandler.kt" in h_path or "ToolHandlerRegistry.kt" in h_path:
        continue
        
    with open(h_path, 'r') as f:
        content = f.read()
        
    content = content.replace("toolCall: String, intentHandler:", "toolCall: String, args: String, intentHandler:")

    # Remove val argStr = toolCall.substringAfter("(").substringBefore(")")
    content = re.sub(r'\s*val argStr = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)\n', '\n', content)
    content = re.sub(r'\s*val argStr = toolCall\.substringAfter\("\("\)\.substringBeforeLast\("\)"\)\n', '\n', content)
    
    # Replace parameter extraction
    content = re.sub(r'val dest = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val dest = args', content)
    content = re.sub(r'val amenity = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val amenity = args', content)
    content = re.sub(r'val query = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val query = args', content)
    content = re.sub(r'val contact = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val contact = args', content)
    content = re.sub(r'val searchTerm = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val searchTerm = args', content)
    content = re.sub(r'val fact = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val fact = args', content)
    content = re.sub(r'val city = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val city = args', content)
    content = re.sub(r'val appName = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val appName = args', content)
    content = re.sub(r'val valueStr = toolCall\.substringAfter\("\("\)\.substringBefore\("\)"\)', 'val valueStr = args', content)
    
    content = content.replace('toolCall.substringAfter("(").substringBefore(")")', 'args')
    content = content.replace('toolCall.substringAfter("(").substringBeforeLast(")")', 'args')

    content = content.replace('Math.abs(Regex("-?\\\\d+(\\\\.\\\\d+)?").find(argStr)?.value?.toDoubleOrNull() ?: 2.0)', 'ParameterParser.extractDouble(args, 2.0)')
    content = content.replace('Math.abs(Regex("-?\\\\d+(\\\\.\\\\d+)?").find(args)?.value?.toDoubleOrNull() ?: 2.0)', 'ParameterParser.extractDouble(args, 2.0)')
    content = content.replace('Math.abs(Regex("\\\\d+").find(argStr)?.value?.toIntOrNull() ?: 1)', 'ParameterParser.extractInt(args, 1)')
    content = content.replace('Math.abs(Regex("\\\\d+").find(args)?.value?.toIntOrNull() ?: 1)', 'ParameterParser.extractInt(args, 1)')

    with open(h_path, 'w') as f:
        f.write(content)


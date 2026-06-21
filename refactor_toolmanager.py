import re

filepath = "app/src/main/java/com/example/gemininano/ToolManager.kt"
with open(filepath, 'r') as f:
    content = f.read()

# Add errorMessage to ToolDefinition
content = content.replace(
    'val successMessage: String?,',
    'val successMessage: String?,\n        val errorMessage: String?,'
)

# Read errorMessage from JSON inside initialize()
content = re.sub(
    r'val successMessage = if \(obj\.has\("success_message"\)\) obj\.getString\("success_message"\) else null',
    'val successMessage = if (obj.has("success_message")) obj.getString("success_message") else null\n                            val errorMessage = if (obj.has("error_message")) obj.getString("error_message") else null',
    content
)

# Update ToolDefinition constructor call
content = re.sub(
    r'successMessage = successMessage,',
    'successMessage = successMessage,\n                                errorMessage = errorMessage,',
    content
)

# Extract args before calling handler
content = re.sub(
    r'val handler = com\.example\.gemininano\.handlers\.ToolHandlerRegistry\.getHandler\(matchedTool\.handlerKey!!, matchedTool\)\s*if \(handler != null\) {',
    'val handler = com.example.gemininano.handlers.ToolHandlerRegistry.getHandler(matchedTool.handlerKey!!, matchedTool)\n            if (handler != null) {\n                val args = toolCall.substringAfter("(").substringBeforeLast(")")',
    content
)

content = re.sub(
    r'val result = handler\.execute\(context, toolCall, intentHandler\)',
    'val result = handler.execute(context, toolCall, args, intentHandler)',
    content
)

content = re.sub(
    r'if \(result\.success && matchedTool\.successMessage != null\) \{\s*return matchedTool\.successMessage\s*\}\s*return result\.message',
    'if (result.success && matchedTool.successMessage != null) {\n                    return matchedTool.successMessage\n                }\n                if (!result.success && matchedTool.errorMessage != null) {\n                    return matchedTool.errorMessage\n                }\n                return result.message',
    content
)


with open(filepath, 'w') as f:
    f.write(content)

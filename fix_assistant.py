with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

# Change val displayMsg to var displayMsg
content = content.replace('val displayMsg = currentText.replace(regex, "").trim()', 'var displayMsg = currentText.replace(regex, "").trim()')

# Insert stripping for displayMsg
target1 = '''                            if (displayMsg.isNotEmpty() && statusText.visibility == View.VISIBLE) {'''
replacement1 = '''                            if (displayMsg.startsWith("Assistant:", ignoreCase = true)) {
                                displayMsg = displayMsg.substring("Assistant:".length).trimStart()
                            }
                            if (displayMsg.startsWith("Response:", ignoreCase = true)) {
                                displayMsg = displayMsg.substring("Response:".length).trimStart()
                            }
                            
                            if (displayMsg.isNotEmpty() && statusText.visibility == View.VISIBLE) {'''
content = content.replace(target1, replacement1)

# Insert stripping for finalMsg
target2 = '''                            responseText.text = parseMarkdown(finalMsg)'''
replacement2 = '''                            if (finalMsg.startsWith("Assistant:", ignoreCase = true)) {
                                finalMsg = finalMsg.substring("Assistant:".length).trimStart()
                            }
                            if (finalMsg.startsWith("Response:", ignoreCase = true)) {
                                finalMsg = finalMsg.substring("Response:".length).trimStart()
                            }
                            
                            responseText.text = parseMarkdown(finalMsg)'''
content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)
print("AssistantSession fixed")

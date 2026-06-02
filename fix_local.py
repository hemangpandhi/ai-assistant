with open("app/src/main/java/com/example/gemininano/LocalLLMActivity.kt", "r") as f:
    content = f.read()

target = '''                            if (currentText.startsWith("Assistant:", ignoreCase = true)) {
                                currentText = currentText.substring("Assistant:".length).trimStart()
                                lastResponseBuilder.clear()
                                lastResponseBuilder.append(currentText)
                            }'''
replacement = '''                            if (currentText.startsWith("Assistant:", ignoreCase = true)) {
                                currentText = currentText.substring("Assistant:".length).trimStart()
                                lastResponseBuilder.clear()
                                lastResponseBuilder.append(currentText)
                            }
                            if (currentText.startsWith("Response:", ignoreCase = true)) {
                                currentText = currentText.substring("Response:".length).trimStart()
                                lastResponseBuilder.clear()
                                lastResponseBuilder.append(currentText)
                            }'''

content = content.replace(target, replacement)

with open("app/src/main/java/com/example/gemininano/LocalLLMActivity.kt", "w") as f:
    f.write(content)
print("LocalLLMActivity fixed")

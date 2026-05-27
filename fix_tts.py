import re

with open('app/src/main/java/com/example/gemininano/AssistantSession.kt', 'r') as f:
    content = f.read()

# Replace the messy onDestroy with proper structure
content = re.sub(r'            override fun onDestroy\(\) \{\n        tts\?\.stop\(\)\n        tts\?\.shutdown\(\)\n        super\.onDestroy\(\)\n    \}\n\}\n    \}\n\}\n', '            }\n        }\n    }\n\n    override fun onDestroy() {\n        tts?.stop()\n        tts?.shutdown()\n        super.onDestroy()\n    }\n}\n', content)

with open('app/src/main/java/com/example/gemininano/AssistantSession.kt', 'w') as f:
    f.write(content)


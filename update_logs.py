import os
import re

def update_file(filepath):
    with open(filepath, "r") as f:
        content = f.read()

    # The regex matches android.util.Log.i("LLMLatency", "[COMPONENT_NAME] THE_MESSAGE")
    # and replaces it with LatencyLogger.log("COMPONENT_NAME", "THE_MESSAGE")
    
    pattern = r'android\.util\.Log\.i\("LLMLatency",\s*"\[(.*?)\]\s*(.*?)"\)'
    
    new_content = re.sub(pattern, r'LatencyLogger.log("\1", "\2")', content)
    
    # We also need to add LatencyLogger.reset() when the voice button is clicked.
    # In AssistantSession.kt: LatencyLogger.log("AssistantSession", "Voice Button Clicked")
    # In LocalLLMActivity.kt: LatencyLogger.log("LocalLLMActivity", "Voice Button Clicked")
    
    new_content = new_content.replace('LatencyLogger.log("AssistantSession", "Voice Button Clicked")',
                                      'LatencyLogger.reset()\n            LatencyLogger.log("AssistantSession", "Voice Button Clicked")')
                                      
    new_content = new_content.replace('LatencyLogger.log("LocalLLMActivity", "Voice Button Clicked")',
                                      'LatencyLogger.reset()\n            LatencyLogger.log("LocalLLMActivity", "Voice Button Clicked")')
    
    if content != new_content:
        with open(filepath, "w") as f:
            f.write(new_content)
        print(f"Updated {filepath}")

update_file("app/src/main/java/com/example/gemininano/LocalLLMActivity.kt")
update_file("app/src/main/java/com/example/gemininano/AssistantSession.kt")

import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

old_feedbacks = """                            if (finalMsg.isEmpty() && toolFeedbacks.isNotEmpty()) {
                                finalMsg = toolFeedbacks.joinToString("\\n")
                            } else if (toolFeedbacks.isNotEmpty()) {
                                val guardrailMessages = toolFeedbacks.filter { it.startsWith("Safety Warning:") }
                                if (guardrailMessages.isNotEmpty()) {
                                    finalMsg += "\\n\\n" + guardrailMessages.joinToString("\\n")
                                }
                            }"""

new_feedbacks = """                            if (finalMsg.isEmpty() && toolFeedbacks.isNotEmpty()) {
                                finalMsg = toolFeedbacks.joinToString("\\n")
                            } else if (toolFeedbacks.isNotEmpty()) {
                                finalMsg += "\\n\\n" + toolFeedbacks.joinToString("\\n")
                            }"""

content = content.replace(old_feedbacks, new_feedbacks)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)

print("Applied fix_feedbacks.py successfully!")

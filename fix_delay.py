import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

old_check = """                if (actionToRun == "QUESTION_FINAL") {
                    btnMic.performClick()
                } else if (actionToRun == "STATEMENT_FINAL_TOOL") {
                    for (job in currentPendingTools) { try { job.await() } catch (e: Exception) {} }
                    kotlinx.coroutines.delay(1500)
                    finish()
                } else if (actionToRun == "STATEMENT_FINAL") {
                    kotlinx.coroutines.delay(2000)
                    finish()
                }"""
new_check = """                if (actionToRun == "QUESTION_FINAL") {
                    btnMic.performClick()
                } else if (actionToRun == "STATEMENT_FINAL_TOOL") {
                    for (job in currentPendingTools) { try { job.await() } catch (e: Exception) {} }
                    kotlinx.coroutines.delay(500)
                    finish()
                } else if (actionToRun == "STATEMENT_FINAL") {
                    kotlinx.coroutines.delay(500)
                    finish()
                }"""
text = text.replace(old_check, new_check)

with open(file_path, "w") as f:
    f.write(text)

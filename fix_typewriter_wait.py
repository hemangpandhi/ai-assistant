import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

old_check = """        if (!actionToRun.isNullOrEmpty()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                if (actionToRun == "QUESTION_FINAL") {
                    btnMic.performClick()
                } else if (actionToRun == "STATEMENT_FINAL_TOOL") {
                    for (job in currentPendingTools) { try { job.await() } catch (e: Exception) {} }
                    kotlinx.coroutines.delay(50)
                    finish()
                } else if (actionToRun == "STATEMENT_FINAL") {
                    kotlinx.coroutines.delay(50)
                    finish()
                }
            }
        }"""
new_check = """        if (!actionToRun.isNullOrEmpty()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                // Wait for the visual typewriter to finish printing all generated text
                while (currentDisplayLength < targetDisplayMessage.length) {
                    kotlinx.coroutines.delay(100)
                }
                
                if (actionToRun == "QUESTION_FINAL") {
                    btnMic.performClick()
                } else if (actionToRun == "STATEMENT_FINAL_TOOL") {
                    for (job in currentPendingTools) { try { job.await() } catch (e: Exception) {} }
                    kotlinx.coroutines.delay(1500)
                    finish()
                } else if (actionToRun == "STATEMENT_FINAL") {
                    kotlinx.coroutines.delay(2000)
                    finish()
                }
            }
        }"""
text = text.replace(old_check, new_check)

with open(file_path, "w") as f:
    f.write(text)

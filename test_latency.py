import subprocess
import time

print("Clearing logcat...")
subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-c"])

# Trigger intent or run validation test case?
# We can just look at AssistantSession.kt and see if there is any other delay.

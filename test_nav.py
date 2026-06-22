import subprocess
import re

print("Running LLMValidationTestSuite...")
result = subprocess.run(
    ["adb", "-s", "3417105H805UGQ", "shell", "am", "instrument", "-w", "-e", "class", "com.example.gemininano.LLMValidationTestSuite", "com.example.gemininano.test/androidx.test.runner.AndroidJUnitRunner"],
    capture_output=True, text=True
)

logcat_result = subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-d", "-s", "LLMTest"], capture_output=True, text=True)
logs = logcat_result.stdout

for line in logs.split('\n'):
    if "Response:" in line and "navigate" in line.lower() or "tokyo" in line.lower() or "san francisco" in line.lower():
        print(line)
    if "Extracted Tool Call:" in line and "navigate" in line.lower():
        print(line)


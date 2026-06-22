import subprocess
import re
import os
from datetime import datetime

print("Building test APK...")
subprocess.run(["./gradlew", "assembleDebugAndroidTest"])
print("Installing test APK...")
subprocess.run(["adb", "-s", "3417105H805UGQ", "install", "-r", "-t", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"])

print("Clearing logcat...")
subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-c"])

print("Running LLMValidationTestSuite...")
result = subprocess.run(
    ["adb", "-s", "3417105H805UGQ", "shell", "am", "instrument", "-w", "-e", "class", "com.example.gemininano.LLMValidationTestSuite", "com.example.gemininano.test/androidx.test.runner.AndroidJUnitRunner"],
    capture_output=True, text=True
)

print("Fetching logcat...")
logcat_result = subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-d", "-s", "LLMTest"], capture_output=True, text=True)
logs = logcat_result.stdout

tests = []
avg_ttft = 0
max_ttft = 0

for line in logs.split('\n'):
    ttft_match = re.search(r"TTFT for '(.*?)': (\d+)ms", line)
    if ttft_match:
        prompt = ttft_match.group(1)
        ttft = int(ttft_match.group(2))
        tests.append({"prompt": prompt, "ttft": ttft, "status": "Tested"})
    
    avg_match = re.search(r"Avg TTFT: (\d+)ms", line)
    if avg_match:
        avg_ttft = int(avg_match.group(1))
        
    max_match = re.search(r"Max TTFT: (\d+)ms", line)
    if max_match:
        max_ttft = int(max_match.group(1))

if not tests:
    print("No TTFT logs found. Test might have crashed.")

html_content = f"""
<!DOCTYPE html>
<html>
<head>
    <title>LLM TTFT Report</title>
    <style>
        body {{ font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #0F172A; color: #F8FAFC; margin: 40px; }}
        h1, h2 {{ color: #38BDF8; }}
        table {{ border-collapse: collapse; width: 100%; margin-top: 20px; background-color: #1E293B; }}
        th, td {{ border: 1px solid #334155; padding: 12px; text-align: left; }}
        th {{ background-color: #0EA5E9; color: white; }}
        .summary {{ display: flex; gap: 20px; margin-bottom: 30px; }}
        .card {{ background-color: #1E293B; padding: 20px; border-radius: 8px; border-left: 4px solid #38BDF8; min-width: 150px; }}
        .value {{ font-size: 24px; font-weight: bold; color: #A78BFA; }}
    </style>
</head>
<body>
    <h1>Automotive AI - Time To First Token (TTFT) Report</h1>
    <p>Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
    
    <div class="summary">
        <div class="card">
            <div>Average TTFT</div>
            <div class="value">{avg_ttft} ms</div>
        </div>
        <div class="card">
            <div>Maximum TTFT</div>
            <div class="value">{max_ttft} ms</div>
        </div>
        <div class="card">
            <div>Total Commands Tested</div>
            <div class="value">{len(tests)}</div>
        </div>
    </div>
    
    <h2>Command Latency Breakdown</h2>
    <table>
        <tr>
            <th>User Prompt</th>
            <th>TTFT (ms)</th>
            <th>Performance Target</th>
        </tr>
"""

for test in tests:
    perf_color = "#10B981" if test['ttft'] < 2500 else "#F59E0B" if test['ttft'] < 4000 else "#EF4444"
    perf_label = "Optimal" if test['ttft'] < 2500 else "Acceptable" if test['ttft'] < 4000 else "Slow"
    html_content += f"""
        <tr>
            <td>{test['prompt']}</td>
            <td style="color: {perf_color}; font-weight: bold;">{test['ttft']} ms</td>
            <td><span style="background-color: {perf_color}; color: white; padding: 4px 8px; border-radius: 4px; font-size: 12px;">{perf_label}</span></td>
        </tr>
    """

html_content += """
    </table>
</body>
</html>
"""

os.makedirs('/home/hemang/.gemini/antigravity/brain/a2146a1c-52f1-4d69-b0df-4b5dac33c1b0', exist_ok=True)
with open('/home/hemang/.gemini/antigravity/brain/a2146a1c-52f1-4d69-b0df-4b5dac33c1b0/ttft_report.html', 'w') as f:
    f.write(html_content)

print("Report generated successfully.")

import subprocess
import re
import time
import sys

def main():
    print("Listening to adb logcat for AutomatedTest results...")
    
    # We want to clear the logcat so we don't read old test runs
    subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-c"])
    
    process = subprocess.Popen(
        ["adb", "-s", "3417105H805UGQ", "logcat"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        universal_newlines=True,
        errors="ignore"
    )
    
    results = []
    
    try:
        for line in process.stdout:
            if "AutomatedTest" not in line:
                continue
                
            print(line.strip())
                
            # Match: Test 1/31 -> PASS. Expected: <TOOL>increaseTemperature
            match = re.search(r"Test (\d+)/(\d+) -> (PASS|FAIL)\. Expected: (.*)", line)
            if match:
                results.append({
                    "test_number": int(match.group(1)),
                    "total_tests": int(match.group(2)),
                    "status": match.group(3),
                    "expected": match.group(4).strip()
                })
                
            if "Test suite complete" in line:
                print("Test suite finished. Generating HTML report...")
                break
    except KeyboardInterrupt:
        print("Interrupted by user.")
    
    process.terminate()
    
    # Generate HTML
    if not results:
        print("No test results found.")
        sys.exit(1)
        
    passed = sum(1 for r in results if r["status"] == "PASS")
    total = len(results)
    
    html = f"""<!DOCTYPE html>
<html>
<head>
<title>HVAC Automated Test Report</title>
<style>
    body {{ font-family: -apple-system, sans-serif; padding: 20px; }}
    h1 {{ color: #333; }}
    .summary {{ padding: 15px; background: #f5f5f5; border-radius: 8px; margin-bottom: 20px; }}
    .PASS {{ color: green; font-weight: bold; }}
    .FAIL {{ color: red; font-weight: bold; }}
    table {{ width: 100%; border-collapse: collapse; }}
    th, td {{ padding: 10px; border: 1px solid #ddd; text-align: left; }}
    th {{ background: #fafafa; }}
</style>
</head>
<body>
    <h1>HVAC & AI Assistant Test Report</h1>
    <div class="summary">
        <h2>Summary</h2>
        <p>Total Tests: {total}</p>
        <p>Passed: {passed}</p>
        <p>Failed: {total - passed}</p>
        <p>Pass Rate: {(passed/total)*100:.1f}%</p>
    </div>
    <table>
        <tr>
            <th>Test #</th>
            <th>Expected Tool Call</th>
            <th>Result</th>
        </tr>
"""
    
    for r in results:
        html += f"""
        <tr>
            <td>{r["test_number"]}</td>
            <td><code>{r["expected"]}</code></td>
            <td class="{r["status"]}">{r["status"]}</td>
        </tr>
"""
        
    html += """
    </table>
</body>
</html>
"""

    with open("/home/hemang/.gemini/antigravity/brain/a2146a1c-52f1-4d69-b0df-4b5dac33c1b0/hvac_report.html", "w") as f:
        f.write(html)
        
    print("Report written to hvac_report.html")

if __name__ == "__main__":
    main()

import subprocess
import time

prompts = [
    "increase temp by 5 degrees", "decrease temp by 10%", "set temperature to 74F",
    "turn on the heater", "it's too cold in here", "defrost the windshield",
    "can you make it warmer", "set temp to 68 degrees", "reduce temperature by 3 degrees",
    "turn the AC down", "make the cabin 72 degrees", "increase heat to maximum",
    "I'm freezing", "I'm boiling", "drop temp by 5 degrees", "crank up the heat",
    "lower temp by 12 percent", "set passenger temp to 70", "change temperature to 75",
    "defrost front window", "defrost rear window", "turn off defroster", "warm up the car",
    "cool down the car", "set temp to 22 celsius", "increase temperature", "decrease temperature",
    "make it hot", "make it cold", "set AC to 65", "increase AC by 2 degrees",
    "temperature up", "temperature down", "temp up", "temp down", "warm me up",
    "cool me down", "set climate to 70", "climate control 68", "adjust temp to 71",
    "modify temperature to 69", "change cabin climate to 73", "turn heat up by 5",
    "turn AC down by 5", "set heat to 80", "set AC to 60", "increase temperature to 75F",
    "decrease temperature to 65F", "set temp 70", "temp 72", "heat 80", "cool 65",
    "defrost", "clear the windshield", "remove fog from window", "defog", "defrost on",
    "defrost off", "make it warmer by 10%", "make it cooler by 10%", "increase temp by 20%",
    "set temp to max", "set temp to min", "turn up the temperature", "turn down the temperature",
    "raise temperature", "lower temperature", "boost heat", "reduce heat", "increase AC",
    "decrease AC", "set internal temp to 74", "cabin temp 70", "car temp 68",
    "make temperature 72", "put temp at 71", "change temp to 69", "adjust heat to 75",
    "set cooling to 65", "increase cabin temp", "decrease cabin temp", "raise cabin temp",
    "lower cabin temp", "turn up cabin temp", "turn down cabin temp", "set temperature 70",
    "set temperature 75", "set temperature 80", "set temperature 60", "set temperature 65",
    "set temperature 68", "set temperature 72", "set temperature 74", "set temperature 71",
    "set temperature 73", "set temperature 69", "set temperature 67", "set temperature 66",
    "set temperature 76", "set temperature 77"
]

results = []

for prompt in prompts:
    subprocess.run(["adb", "logcat", "-c"])
    print(f"Testing: '{prompt}'")
    subprocess.run(["adb", "shell", "am", "broadcast", "-a", "com.example.gemininano.TEST_QUERY", "-e", "query", f'"{prompt}"'], capture_output=True)
    
    success = False
    details = ""
    for _ in range(30): # wait up to 15 seconds
        time.sleep(0.5)
        log = subprocess.run(["adb", "logcat", "-d", "-s", "AutomatedTest"], capture_output=True, text=True).stdout
        
        if "SUCCESS" in log or "FAILURE" in log:
            if "SUCCESS" in log:
                success = True
            lines = log.strip().split('\n')
            for line in lines:
                if "SUCCESS" in line or "FAILURE" in line:
                    details = line.split("AutomatedTest: ")[-1]
            break
            
    if not details:
        details = "TIMEOUT"
        
    print(f"Result: {success} ({details})")
    results.append({"prompt": prompt, "success": success, "details": details})

with open("/home/hemang/.gemini/antigravity/brain/39f257d4-d3b7-406d-a821-039edd853d99/artifacts/test_results.md", "w") as f:
    f.write("# Automated LLM Prompt Test Results\n\n")
    f.write(f"Total Tests: {len(prompts)}\n")
    f.write(f"Pass: {sum(1 for r in results if r['success'])}\n")
    f.write(f"Fail: {sum(1 for r in results if not r['success'])}\n\n")
    f.write("| Prompt | Status | Details |\n")
    f.write("|---|---|---|\n")
    for r in results:
        status = "✅ PASS" if r['success'] else "❌ FAIL"
        f.write(f"| {r['prompt']} | {status} | {r['details']} |\n")
        
print("Tests complete.")

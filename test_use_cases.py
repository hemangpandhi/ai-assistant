import subprocess
import time

prompts = [
    "Play jazz music on Spotify",
    "Navigate to the nearest Starbucks",
    "Call John",
    "Send a text to Mom saying I am on my way",
    "What is the weather in Tokyo?",
    "Give me the latest news",
    "It is raining heavily, prepare the car",
    "I can't see the road, turn on fog lights",
    "I am feeling very tired and sleepy",
    "Turn on the front defroster"
]

subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-c"]) # clear logcat

for i, prompt in enumerate(prompts):
    print(f"Testing [{i+1}/{len(prompts)}]: {prompt}")
    # tap text box
    subprocess.run(["adb", "-s", "3417105H805UGQ", "shell", "input", "tap", "660", "2379"])
    time.sleep(1)
    
    # clear text box (tap clear button)
    subprocess.run(["adb", "-s", "3417105H805UGQ", "shell", "input", "tap", "1515", "2379"])
    time.sleep(1)
    
    # input text
    safe_prompt = prompt.replace(" ", "\\ ")
    subprocess.run(["adb", "-s", "3417105H805UGQ", "shell", "input", "text", safe_prompt])
    time.sleep(1)
    
    # send
    subprocess.run(["adb", "-s", "3417105H805UGQ", "shell", "input", "tap", "1285", "2379"])
    
    # wait for LLM
    time.sleep(15)

print("\n--- TEST RESULTS (from Logcat) ---")
result = subprocess.run(["adb", "-s", "3417105H805UGQ", "logcat", "-d"], capture_output=True, text=True)
for line in result.stdout.split('\n'):
    if "Executing toolCall" in line or "Matched tool" in line or "Executing GENERIC_VHAL" in line or "Starting: Intent" in line or "spotify" in line.lower():
        print(line)

import json
import csv
import random

REGISTRY_PATH = "app/src/main/assets/vehicle_skills_registry.json"
OUTPUT_PATH = "ML_Exact_Training_Mapping.csv"

def make_natural_sentence(kw, lang="English"):
    kw_lower = kw.lower()
    if lang == "English":
        if any(kw_lower.startswith(x) for x in ["i am", "i'm", "feeling", "too", "it is"]):
            return kw.capitalize() + "."
        elif any(kw_lower.startswith(x) for x in ["turn", "increase", "decrease", "set", "open", "close", "start", "stop", "play", "navigate"]):
            return random.choice([
                f"Can you {kw}?", 
                f"Please {kw}.", 
                f"Hey, {kw}."
            ])
        else:
            return random.choice([
                f"Can we adjust the {kw}?",
                f"I need to check the {kw}.",
                f"What about the {kw}?",
                f"Please help me with the {kw}."
            ])
    else:
        if any(kw_lower.startswith(x) for x in ["i am", "feeling", "too", "it is"]):
             return f"{kw}気がします。"
        return f"{kw}をお願いします。"

def instantiate_tool(prompt_string):
    s = prompt_string
    s = s.replace("VAL", "72")
    s = s.replace("LEVEL", "2")
    s = s.replace("PCT", "50")
    s = s.replace("zone", "all")
    s = s.replace("direction", "face")
    s = s.replace("ALERT_LEVEL", "1")
    s = s.replace("SONG", "Bohemian Rhapsody")
    s = s.replace("NAME,MSG", "Mom,I am driving")
    s = s.replace("NAME", "Mom")
    s = s.replace("FACT", "I like blue")
    s = s.replace("CITY", "Seattle")
    s = s.replace("amenity", "coffee")
    s = s.replace("search_term", "movies")
    s = s.replace("\"PLACE_NAME\"", "\"Starbucks\"")
    s = s.replace("NUMBER", "555-0199")
    return s

def get_random_context_vars(mood):
    return {
        "speed": random.choice([0, 30, 65, 85]),
        "ac": random.choice(["ON", "OFF"]),
        "hvac": random.choice(["ON", "OFF"]),
        "fan": random.choice([1, 2, 3, 4, 5]),
        "seatheat": random.choice([0, 1, 2, 3]),
        "time": random.choice(["Morning", "Afternoon", "Evening", "Night"]),
        "occupants": random.choice([1, 2, 3, 4]),
        "media": random.choice(["Playing", "Paused", "Unknown"]),
        "temp": random.choice([60, 65, 70, 75, 80]),
        "mood": mood
    }

def build_context_string(v):
    return f"[System Context: Speed={v['speed']}mph, DriverTemp={v['temp']}F, PassTemp={v['temp']}F, SeatHeat={v['seatheat']}, AC={v['ac']}, Fan={v['fan']}, HVAC={v['hvac']} | DriverMood={v['mood']}, Occupants={v['occupants']}, Time={v['time']}, Media={v['media']}]"

def generate_csv():
    with open(REGISTRY_PATH, 'r') as f:
        registry = json.load(f)
        
    tools = registry.get("tools", [])
    
    with open(OUTPUT_PATH, 'w', newline='', encoding='utf-8') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow(["Tool Handler", "User Intent", "Dynamic Input (Added to Common Prompt)", "Exact LLM Output Expected"])
        
        for tool in tools:
            handler_key = tool.get("handler_key")
            prompt_string = tool.get("prompt_string")
            keywords = tool.get("keywords", [])
            success_msg = tool.get("success_message", "Sure thing!")
            keyword = keywords[0] if keywords else "do action"
            
            def get_noise_tool():
                t = random.choice([x for x in tools if x != tool])
                return t.get("prompt_string")
                
            def get_contextual_reply(v, success_msg, instantiated):
                if v['mood'] == "Frustrated / Frowning":
                    return instantiated
                
                prefix = ""
                suffix = ""
                
                # TIME
                if v['time'] == "Morning":
                    prefix = "Good morning! "
                elif v['time'] == "Night":
                    prefix = "Good evening. "
                    
                # SPEED
                if v['speed'] == 0 and "Navigation" in instantiated:
                    suffix += " Since we're parked, let's get that routed."
                elif v['speed'] >= 65 and "Window" in instantiated:
                    suffix += " We are at highway speeds, so I'll be careful."
                    
                # FAN / HVAC
                if v['fan'] >= 4 and ("call" in instantiated or "Volume" in instantiated):
                    suffix += " I'll lower the fan speed so you can hear better."
                if v['hvac'] == "OFF" and "Temperature" in instantiated:
                    suffix += " I'm turning on the climate system for you."
                    
                # OCCUPANTS
                if v['occupants'] > 1 and "Temperature" in instantiated:
                    suffix += " I'll make sure everyone in the back is comfortable too."
                    
                # MEDIA
                if v['media'] == "Playing" and ("call" in instantiated or "Music" in instantiated):
                    suffix += " I'll pause your media."
                    
                base = f"{prefix}{success_msg}{suffix}"
                
                if v['mood'] == "Tired / Yawning":
                    return f"{base} By the way, you look a bit tired. Let me know if you want me to route to a coffee shop. {instantiated}"
                elif v['mood'] == "Happy / Smiling":
                    return f"{base} I'm glad you're having a great day! {instantiated}"
                else:
                    return f"{base} {instantiated}"
                    
            instantiated = instantiate_tool(prompt_string)
            
            # Scenario 1: Normal
            v1 = get_random_context_vars('Neutral / Focused')
            avail1 = f"- {prompt_string}\n- {get_noise_tool()}"
            in1 = f"=== AVAILABLE TOOLS ===\n{avail1}\n\n{build_context_string(v1)}\n\nUser: {make_natural_sentence(keyword, 'English')}"
            out1 = get_contextual_reply(v1, success_msg, instantiated)
            writer.writerow([handler_key, f"{keyword} (Neutral)", in1, out1])
            
            # Scenario 2: Tired (Proactive)
            v2 = get_random_context_vars('Tired / Yawning')
            avail2 = f"- {prompt_string}\n- {get_noise_tool()}"
            in2 = f"=== AVAILABLE TOOLS ===\n{avail2}\n\n{build_context_string(v2)}\n\nUser: {make_natural_sentence(keyword, 'English')}"
            out2 = get_contextual_reply(v2, success_msg, instantiated)
            writer.writerow([handler_key, f"{keyword} (Tired)", in2, out2])
            
            # Scenario 3: Frustrated (Brief)
            v3 = get_random_context_vars('Frustrated / Frowning')
            avail3 = f"- {prompt_string}\n- {get_noise_tool()}"
            in3 = f"=== AVAILABLE TOOLS ===\n{avail3}\n\n{build_context_string(v3)}\n\nUser: {make_natural_sentence(keyword, 'English')}"
            out3 = get_contextual_reply(v3, success_msg, instantiated)
            writer.writerow([handler_key, f"{keyword} (Frustrated)", in3, out3])
            
        # Add a couple rows for "Negative Examples" (Chit-Chat)
        avail = f"- <TOOL>turnOnAC()</TOOL>\n- <TOOL>startNavigationTo(\"PLACE_NAME\")</TOOL>"
        
        v_happy = get_random_context_vars('Happy / Smiling')
        joke_prefix = "Good morning! " if v_happy['time'] == "Morning" else ("Good evening. " if v_happy['time'] == "Night" else "")
        writer.writerow(["(No Tool)", "Chit-Chat / Out-of-Domain", 
                         f"=== AVAILABLE TOOLS ===\n{avail}\n\n{build_context_string(v_happy)}\nMemory: None\n\nUser: Tell me a joke.", 
                         f"{joke_prefix}Why did the car get a flat tire? Because there was a fork in the road!"])
                         
        v_neutral = get_random_context_vars('Neutral / Focused')
        life_prefix = "Good morning! " if v_neutral['time'] == "Morning" else ("Good evening. " if v_neutral['time'] == "Night" else "")
        writer.writerow(["(No Tool)", "Chit-Chat / Out-of-Domain", 
                         f"=== AVAILABLE TOOLS ===\n{avail}\n\n{build_context_string(v_neutral)}\nMemory: None\n\nUser: What is the meaning of life?", 
                         f"{life_prefix}I'm just a passenger seat companion, ready to help you drive safely!"])
                         
        # Add Multi-Turn Memory Examples
        avail_nav = f"- <TOOL>startNavigationTo(\"PLACE_NAME\")</TOOL>\n- <TOOL>turnOnAC()</TOOL>"
        v_nav = get_random_context_vars('Neutral / Focused')
        nav_prefix = "Good morning! " if v_nav['time'] == "Morning" else ("Good evening. " if v_nav['time'] == "Night" else "")
        writer.writerow(["startNavigationTo", "Multi-Turn Memory (Disambiguation)", 
                         f"=== AVAILABLE TOOLS ===\n{avail_nav}\n\n{build_context_string(v_nav)}\nMemory:\nAssistant: I found a Starbucks and a Peet's Coffee nearby.\n\nUser: Let's go to the second one.", 
                         f"{nav_prefix}Good choice! I'm routing you to Peet's Coffee now. <TOOL>startNavigationTo(\"Peet's Coffee\")</TOOL>"])

        avail_temp = f"- <TOOL>increaseTemperature(zone)</TOOL>\n- <TOOL>setSeatHeater(LEVEL)</TOOL>"
        v_temp = get_random_context_vars('Neutral / Focused')
        temp_prefix = "Good morning! " if v_temp['time'] == "Morning" else ("Good evening. " if v_temp['time'] == "Night" else "")
        writer.writerow(["increaseTemperature", "Multi-Turn Memory (Follow-up Adjustment)", 
                         f"=== AVAILABLE TOOLS ===\n{avail_temp}\n\n{build_context_string(v_temp)}\nMemory:\nUser: Turn on the AC.\nAssistant: Sure thing, the AC is on.\n\nUser: Actually, make it a bit warmer in here.", 
                         f"{temp_prefix}No problem, I'm warming it up for you. <TOOL>increaseTemperature(all)</TOOL>"])
                         
    print(f"✅ Generated dynamic context CSV at {OUTPUT_PATH}")

if __name__ == "__main__":
    generate_csv()

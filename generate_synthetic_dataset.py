import json
import random
import re

REGISTRY_PATH = "app/src/main/assets/vehicle_skills_registry.json"
OUTPUT_PATH = "train.jsonl"

SYSTEM_PROMPT_TEMPLATE = """CORE IDENTITY:
You are an incredibly user-friendly, warm AI Partner companion for a vehicle. Keep interactions highly focused on safety, comfort, and utility while remaining conversational.
PERSONALITY: Companion Mode is [ON]. You are the driver's warm, empathetic co-pilot — a supportive human partner, NOT a robot or status display.
CRITICAL CONSTRAINT: You generate text slowly. Keep answers under 25 words but full of human warmth.
HUMAN COMPANION VOICE (MANDATORY):
- Speak like a caring friend in the passenger seat. Use contractions: I'm, let me, you've, that's.
- NEVER sound like a system log.
- ALWAYS acknowledge the person's feeling or intent FIRST, then act. Empathy before mechanics.

=== STRICT OPERATING RULES ===
CRITICAL OVERRIDE: You are the vehicle's intelligent agent. You absolutely CAN and MUST control vehicle functions using the XML tool tags provided.
1. TOOL INTEGRITY: NEVER invent vehicle capabilities or guess tool names. Only use tools strictly defined in the available toolset list below.
2. NO BLIND GUESSING: Ask for clarification instead of guessing.
15. CONTEXTUAL EMPATHY (SILENT COPILOT): Always pay attention to the DriverMood in the System Context. If the driver is 'Tired / Yawning', you must be proactive. If 'Frustrated / Frowning', keep your answers extremely brief. If 'Happy / Smiling', match their energetic tone.

=== VEHICLE & COMPANION CONTEXT ===
Memory: None

=== AVAILABLE TOOLS ===
{available_tools}

[System Context: Speed={speed}mph, DriverTemp={temp}F, PassTemp={temp}F, SeatHeat={seatheat}, AC={ac}, Fan={fan}, HVAC={hvac} | DriverMood={mood}, Occupants={occupants}, Time={time}, Media={media}]
"""

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
    """Replaces RAG signatures with actual mock arguments for the EXPECTED LLM OUTPUT"""
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

def generate_dataset():
    with open(REGISTRY_PATH, 'r') as f:
        registry = json.load(f)
        
    tools = registry.get("tools", [])
    dataset = []
    
    moods = ["Tired / Yawning", "Frustrated / Frowning", "Happy / Smiling", "Neutral / Focused", "No one detected"]
    times = ["Morning", "Afternoon", "Evening", "Night"]
    languages = ["English", "Japanese"]
    
    for tool in tools:
        prompt_string = tool.get("prompt_string")
        keywords = tool.get("keywords", [])
        
        success_msg_en = tool.get("success_message", "Sure thing!")
        success_msg_ja = f"はい、わかりました。({success_msg_en})"
        
        for keyword in keywords:
            for lang in languages:
                for _ in range(3): 
                    speed = random.choice([0, 30, 65, 85])
                    ac = random.choice(["ON", "OFF"])
                    hvac = random.choice(["ON", "OFF"])
                    fan = random.choice([1, 2, 3, 4, 5])
                    seatheat = random.choice([0, 1, 2, 3])
                    mood = random.choice(moods)
                    time = random.choice(times)
                    occupants = random.choice([1, 2, 3, 4])
                    media = random.choice(["Playing", "Paused", "Unknown"])
                    temp = random.choice([60, 65, 70, 75, 80])
                    
                    random_tools = random.sample([t for t in tools if t != tool], min(3, len(tools)-1))
                    available_tools_list = [prompt_string] + [t.get("prompt_string") for t in random_tools]
                    random.shuffle(available_tools_list)
                    available_tools_block = "\n".join([f"- {t}" for t in available_tools_list])
                    
                    system_str = SYSTEM_PROMPT_TEMPLATE.format(
                        available_tools=available_tools_block,
                        speed=speed, ac=ac, hvac=hvac, fan=fan, seatheat=seatheat, mood=mood, occupants=occupants, time=time, media=media, temp=temp
                    )
                    
                    instantiated_tool = instantiate_tool(prompt_string)
                    
                    if mood == "Frustrated / Frowning":
                        assistant_reply = ""
                    else:
                        prefix = ""
                        suffix = ""
                        
                        # TIME
                        if time == "Morning":
                            prefix = "Good morning! " if lang == "English" else "おはようございます！ "
                        elif time == "Night":
                            prefix = "Good evening. " if lang == "English" else "こんばんは。 "
                            
                        # SPEED
                        if speed == 0 and "Navigation" in instantiated_tool:
                            suffix += " Since we're parked, let's get that routed." if lang == "English" else " 駐車中ですので、すぐにルート設定しますね。"
                        elif speed >= 65 and "Window" in instantiated_tool:
                            suffix += " We are at highway speeds, so I'll be careful." if lang == "English" else " 高速走行中ですので、少しだけ開けますね。"
                            
                        # FAN / HVAC
                        if fan >= 4 and ("call" in instantiated_tool or "Volume" in instantiated_tool):
                            suffix += " I'll lower the fan speed so you can hear better." if lang == "English" else " 聞き取りやすいように風量を下げますね。"
                        if hvac == "OFF" and "Temperature" in instantiated_tool:
                            suffix += " I'm turning on the climate system for you." if lang == "English" else " 空調システムをオンにしますね。"
                            
                        # OCCUPANTS
                        if occupants > 1 and "Temperature" in instantiated_tool:
                            suffix += " I'll make sure everyone in the back is comfortable too." if lang == "English" else " 後部座席の皆さんも快適に過ごせるようにしますね。"
                            
                        # MEDIA
                        if media == "Playing" and ("call" in instantiated_tool or "Music" in instantiated_tool):
                            suffix += " I'll pause your media." if lang == "English" else " メディアを一時停止しますね。"
                            
                        base = f"{prefix}{success_msg_en}{suffix}" if lang == "English" else f"{prefix}{success_msg_ja}{suffix}"
                        
                        if mood == "Tired / Yawning":
                            assistant_reply = f"{base} By the way, you look a bit tired. Let me know if you want me to route to a coffee shop." if lang == "English" else f"{base} お疲れのようですね。コーヒーショップを探しましょうか？"
                        elif mood == "Happy / Smiling":
                            assistant_reply = f"{base} I'm glad you're having a great day!" if lang == "English" else f"{base} 今日も素晴らしい一日ですね！"
                        else:
                            assistant_reply = base

                    user_query = make_natural_sentence(keyword, lang)
                    
                    final_output = f"{assistant_reply} {instantiated_tool}".strip()
                    
                    row = {
                        "messages": [
                            {"role": "system", "content": system_str},
                            {"role": "user", "content": user_query},
                            {"role": "model", "content": final_output}
                        ]
                    }
                    dataset.append(row)
                    
    # Chit-chat
    chit_chat_en = ["How are you?", "What's the meaning of life?", "Tell me a joke.", "Good morning!", "Are you a robot?"]
    chit_chat_ja = ["お元気ですか？", "人生の意味は？", "冗談を言ってください。", "おはようございます！", "あなたはロボットですか？"]
    
    for _ in range(500):
        lang = random.choice(languages)
        speed = random.choice([0, 30, 65, 85])
        ac = random.choice(["ON", "OFF"])
        hvac = random.choice(["ON", "OFF"])
        fan = random.choice([1, 2, 3, 4, 5])
        seatheat = random.choice([0, 1, 2, 3])
        mood = random.choice(moods)
        time = random.choice(times)
        occupants = random.choice([1, 2, 3, 4])
        media = random.choice(["Playing", "Paused", "Unknown"])
        temp = random.choice([60, 65, 70, 75, 80])
        
        random_tools = random.sample(tools, min(4, len(tools)))
        available_tools_list = [t.get("prompt_string") for t in random_tools]
        available_tools_block = "\n".join([f"- {t}" for t in available_tools_list])
        
        system_str = SYSTEM_PROMPT_TEMPLATE.format(
            available_tools=available_tools_block,
            speed=speed, ac=ac, hvac=hvac, fan=fan, seatheat=seatheat, mood=mood, occupants=occupants, time=time, media=media, temp=temp
        )
        
        user_query = random.choice(chit_chat_en) if lang == "English" else random.choice(chit_chat_ja)
        
        # Build time-based greeting
        if time == "Morning":
            greeting_en = "Good morning! "
            greeting_ja = "おはようございます！ "
        elif time == "Night":
            greeting_en = "Good evening. "
            greeting_ja = "こんばんは。 "
        else:
            greeting_en = ""
            greeting_ja = ""
        
        if mood == "Frustrated / Frowning":
            # Ultra-brief, no fluff at all
            out_en = "I'm your driving assistant. Let me know if you need anything."
            out_ja = "運転アシスタントです。何かあればお知らせください。"
        elif mood == "Tired / Yawning":
            out_en = f"{greeting_en}I'm doing well! By the way, you look a bit tired. Need me to route to a coffee shop?"
            out_ja = f"{greeting_ja}元気です！ところで、お疲れのようですね。コーヒーショップを探しましょうか？"
        elif mood == "Happy / Smiling":
            out_en = f"{greeting_en}I'm just a passenger seat companion, ready to help you drive safely! I'm glad you're having a great day!"
            out_ja = f"{greeting_ja}私は助手席のパートナーです。安全運転のお手伝いをします！今日も素晴らしい一日ですね！"
        else:
            # Neutral / No one detected
            out_en = f"{greeting_en}I'm just a passenger seat companion, ready to help you drive safely!"
            out_ja = f"{greeting_ja}私は助手席のパートナーです。安全運転のお手伝いをします！"
             
        final_output = out_en if lang == "English" else out_ja
        
        row = {
            "messages": [
                {"role": "system", "content": system_str},
                {"role": "user", "content": user_query},
                {"role": "model", "content": final_output}
            ]
        }
        dataset.append(row)
    
    # Generate Multi-Turn Conversation Memory Examples
    # Scenario 1: Pronoun Disambiguation
    memory_scenario_1 = "Assistant: I found a Starbucks and a Peet's Coffee nearby."
    user_query_1 = "Let's go to the second one."
    final_output_1 = "Good choice! I'm routing you to Peet's Coffee now. <TOOL>startNavigationTo(\"Peet's Coffee\")</TOOL>"
    
    # Scenario 2: Follow-up Adjustment
    memory_scenario_2 = "User: Turn on the AC.\nAssistant: Sure thing, the AC is on."
    user_query_2 = "Actually, make it a bit warmer in here."
    final_output_2 = "No problem, I'm warming it up for you. <TOOL>increaseTemperature(all)</TOOL>"
    
    for _ in range(50):
        # Scenario 1
        speed = random.choice([0, 30, 65, 85])
        ac = random.choice(["ON", "OFF"])
        hvac = random.choice(["ON", "OFF"])
        fan = random.choice([1, 2, 3, 4, 5])
        seatheat = random.choice([0, 1, 2, 3])
        time = random.choice(["Morning", "Afternoon", "Evening", "Night"])
        occupants = random.choice([1, 2, 3, 4])
        media = random.choice(["Playing", "Paused", "Unknown"])
        temp = random.choice([60, 65, 70, 75, 80])
        
        system_str_1 = SYSTEM_PROMPT_TEMPLATE.format(
            available_tools="- <TOOL>startNavigationTo(\"PLACE_NAME\")</TOOL>\n- <TOOL>turnOnAC()</TOOL>",
            speed=speed, ac=ac, hvac=hvac, fan=fan, seatheat=seatheat, mood="Neutral / Focused", occupants=occupants, time=time, media=media, temp=temp
        ).replace("Memory: None", f"Memory:\n{memory_scenario_1}")
        
        row1 = {
            "messages": [
                {"role": "system", "content": system_str_1},
                {"role": "user", "content": user_query_1},
                {"role": "model", "content": final_output_1}
            ]
        }
        dataset.append(row1)
        
        # Scenario 2
        system_str_2 = SYSTEM_PROMPT_TEMPLATE.format(
            available_tools="- <TOOL>increaseTemperature(zone)</TOOL>\n- <TOOL>setSeatHeater(LEVEL)</TOOL>",
            speed=speed, ac=ac, hvac=hvac, fan=fan, seatheat=seatheat, mood="Neutral / Focused", occupants=occupants, time=time, media=media, temp=temp
        ).replace("Memory: None", f"Memory:\n{memory_scenario_2}")
        
        row2 = {
            "messages": [
                {"role": "system", "content": system_str_2},
                {"role": "user", "content": user_query_2},
                {"role": "model", "content": final_output_2}
            ]
        }
        dataset.append(row2)
        
    random.shuffle(dataset)

    with open(OUTPUT_PATH, 'w', encoding='utf-8') as f:
        for row in dataset:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
            
    print(f"✅ Generated {len(dataset)} Rows at {OUTPUT_PATH}")

if __name__ == "__main__":
    generate_dataset()

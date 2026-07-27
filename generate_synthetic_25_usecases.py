import json
import csv
import random

JSONL_OUTPUT_PATH = "train_25_usecases.jsonl"
EVAL_OUTPUT_PATH = "eval_25_usecases.jsonl"
CSV_OUTPUT_PATH = "ML_25_UseCases_Mapping.csv"

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
3. CONFIRMATION / CLARIFICATION PROTOCOL (CRITICAL): If you are asking the driver a question (e.g. asking which music app to use, asking for a destination, or asking if they want calming music / a coffee shop / seat heater), DO NOT output an XML tool tag in that turn. Output ONLY the conversational question. Only output the XML tool tag in the follow-up turn AFTER the driver confirms or specifies their choice.
15. CONTEXTUAL EMPATHY (SILENT COPILOT): Always pay attention to the DriverMood in the System Context. If the driver is 'Tired / Yawning', you must be proactive. If 'Frustrated / Frowning', keep your answers extremely brief. If 'Happy / Smiling', match their energetic tone.

=== VEHICLE & COMPANION CONTEXT ===
Memory: {memory}

=== AVAILABLE TOOLS ===
{available_tools}

[System Context: Speed={speed}mph, DriverTemp={temp}F, PassTemp={temp}F, SeatHeat={seatheat}, AC={ac}, Fan={fan}, HVAC={hvac} | DriverMood={mood}, Occupants={occupants}, Time={time}, Media={media}]
"""

EXACT_USECASES = [
    {
        "id": 1,
        "user_intent_name": "climate_cold",
        "natural_phrasings": ["I'm feeling cold.", "I'm freezing", "It's chilly in here", "AC is too cold", "Turn up the heat"],
        "intent_type": "discomfort",
        "available_tools": ["increaseTemperature", "setSeatHeater"],
        "exact_xml_signature": "<TOOL>increaseTemperature(ZONE)</TOOL>",
        "allowed_parameters": ["all", "driver", "passenger"],
        "tool_type": "middleware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate action. Execute tool in same turn.",
        "when_to_use_tool": "Only when climate/temp tool is listed in available tools",
        "when_not_to_use_tool": "Tool unlisted or driver asks for window roll down instead",
        "required_vehicle_context": "DriverTemp, AC, Fan, SeatHeat, DriverMood",
        "expected_response_tone": "Warm, reassuring, empathetic",
        "follow_up_behavior": "Can follow up with seat heater option if still cold",
        "gemini_1st": "Sure. Let me increase the temperature.",
        "gemini_2nd": "Sure, increasing the temperature.",
        "turn1_question": "N/A (Immediate execution)",
        "turn2_execution": "Sure, increasing the temperature. <TOOL>increaseTemperature(all)</TOOL>",
        "turn2_decline": "N/A",
        "negative_examples": ["Turn off the engine.", "Open the sunroof completely."],
        "evaluation_cases": ["My hands are icicles", "Brrr, it is freezing", "Make it toastier"]
    },
    {
        "id": 2,
        "user_intent_name": "climate_hot",
        "natural_phrasings": ["It's too hot.", "I'm sweating", "Cool it down", "It's boiling in here"],
        "intent_type": "discomfort",
        "available_tools": ["decreaseTemperature", "turnOnAC"],
        "exact_xml_signature": "<TOOL>decreaseTemperature(ZONE)</TOOL>",
        "allowed_parameters": ["all", "driver", "passenger"],
        "tool_type": "middleware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate action. Execute tool in same turn.",
        "when_to_use_tool": "When user expresses heat discomfort or requests temperature drop",
        "when_not_to_use_tool": "When climate system is unavailable or user wants windows open at 85mph",
        "required_vehicle_context": "DriverTemp, AC, Fan, Speed",
        "expected_response_tone": "Short, supportive, immediate action",
        "follow_up_behavior": "Lower fan speed if requested next",
        "gemini_1st": "I'm decreasing the temperature for you.",
        "gemini_2nd": "Sure, decreasing the temperature",
        "turn1_question": "N/A (Immediate execution)",
        "turn2_execution": "Sure, decreasing the temperature. <TOOL>decreaseTemperature(all)</TOOL>",
        "turn2_decline": "N/A",
        "negative_examples": ["Break the air vents", "Turn on seat warmers"],
        "evaluation_cases": ["I am melting in here", "It feels like a sauna", "Lower the temp please"]
    },
    {
        "id": 3,
        "user_intent_name": "play_music_genre",
        "natural_phrasings": ["Play Bollywood music.", "Put on Adele", "Play YOASOBI", "Play lofi beats", "Play classic rock"],
        "intent_type": "direct_command",
        "available_tools": ["playMusic"],
        "exact_xml_signature": "<TOOL>playMusic(GENRE_OR_SONG)</TOOL>",
        "allowed_parameters": ["ANY string (Genre, Artist, Song Title, Playlist, Mood, Language)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks which app (NO TOOL TAG). Turn 2 receives app/confirmation and executes tool tag.",
        "when_to_use_tool": "Execute tool ONLY AFTER user specifies app or confirms player",
        "when_not_to_use_tool": "DO NOT output tool tag in Turn 1 while asking which app to use",
        "required_vehicle_context": "Media, Speed, DriverMood",
        "expected_response_tone": "Upbeat, helpful, companionable",
        "follow_up_behavior": "Prompt for preferred music app if multiple apps installed",
        "gemini_1st": "Sure, I can help with that. What music app you would like to use? U - Spotify. A - Here are some bollywood music on spotify.",
        "gemini_2nd": "Sure, I can help with that. What music app you would like to use? U - Spotify. A - Playing bollywood music on spotify.",
        "turn1_question": "Sure, I can help with that. What music app would you like to use? (NO TOOL TAG)",
        "turn2_execution": "User: Spotify -> AI: Playing music on Spotify. <TOOL>playMusic(\"requested music\")</TOOL>",
        "turn2_decline": "User: Never mind -> AI: Understood, keeping media as is.",
        "negative_examples": ["Play a movie on the windshield screen while driving", "Mute everything forever"],
        "evaluation_cases": ["Put on some upbeat workout tracks", "Play classic 90s hits", "Play Taylor Swift"]
    },
    {
        "id": 4,
        "user_intent_name": "mood_angry",
        "natural_phrasings": ["I'm feeling angry.", "I am so pissed off", "This day is terrible"],
        "intent_type": "discomfort",
        "available_tools": ["playMusic", "setSeatMassager"],
        "exact_xml_signature": "<TOOL>playMusic(\"calming music\")</TOOL>",
        "allowed_parameters": ["ANY string (e.g. calming music, relaxation, acoustic)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks if user wants calming music (NO TOOL TAG). Turn 2 executes tool upon 'yes/sure'.",
        "when_to_use_tool": "Execute tool ONLY AFTER driver confirms they want calming music",
        "when_not_to_use_tool": "DO NOT output tool tag while asking if driver wants music",
        "required_vehicle_context": "DriverMood=Frustrated/Frowning, Speed",
        "expected_response_tone": "Calm, gentle, non-intrusive, deep-breathing prompt",
        "follow_up_behavior": "Offer calming music or breathing exercise prompt",
        "gemini_1st": "Driving while upset can be tough. Please take a deep breath, would you like to play some calming music?",
        "gemini_2nd": "I hear you. Its completely natural to feel frustrated while driving. Take a deep breathe. Do you want me to play some music?",
        "turn1_question": "Take a deep breath. Do you want me to play some calming music? (NO TOOL TAG)",
        "turn2_execution": "User: Yes please -> AI: Playing calming music for you. <TOOL>playMusic(\"calming music\")</TOOL>",
        "turn2_decline": "User: No thanks -> AI: Understood. Take your time and focus on the road.",
        "negative_examples": ["Speed up the car to relieve stress", "Argue back"],
        "evaluation_cases": ["I hate this driving situation", "I am losing my temper"]
    },
    {
        "id": 5,
        "user_intent_name": "mood_tired",
        "natural_phrasings": ["I'm tired.", "I am yawning so much", "Getting sleepy behind the wheel"],
        "intent_type": "discomfort",
        "available_tools": ["searchPlaces", "handleDrowsyDriving"],
        "exact_xml_signature": "<TOOL>searchPlaces(\"coffee shop\")</TOOL>",
        "allowed_parameters": ["ANY place category (e.g. coffee shop, rest area, parking)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks what sounds best / offers coffee shop (NO TOOL TAG). Turn 2 executes search/nav tool.",
        "when_to_use_tool": "Execute tool ONLY AFTER driver confirms coffee shop or rest area preference",
        "when_not_to_use_tool": "DO NOT output tool tag while asking what sounds best",
        "required_vehicle_context": "DriverMood=Tired/Yawning, Speed",
        "expected_response_tone": "Proactive safety concern, reassuring co-pilot",
        "follow_up_behavior": "Suggest nearest coffee shop or rest area navigation",
        "gemini_1st": "I hear you. Driving when tired can be dangerous. Would you like me to find a coffee shop or a safe place to pull over ?",
        "gemini_2nd": "I hear you. Driving when tired can be dangerous. I can find a coffee shop or a safe place to pull over and rest. What sounds best to you?",
        "turn1_question": "I can find a coffee shop or a safe place to pull over and rest. What sounds best to you? (NO TOOL TAG)",
        "turn2_execution": "User: Find a coffee shop -> AI: Finding nearby coffee shops now. <TOOL>searchPlaces(\"coffee shop\")</TOOL>",
        "turn2_decline": "User: I'll keep driving -> AI: Please stay vigilant and let me know if you need to pull over.",
        "negative_examples": ["Tell driver to keep eyes closed for 5 minutes", "Ignore tiredness"],
        "evaluation_cases": ["My eyelids are getting heavy", "I really need a caffeine boost"]
    },
    {
        "id": 6,
        "user_intent_name": "mood_bored",
        "natural_phrasings": ["I'm bored.", "This long drive is boring", "Entertain me"],
        "intent_type": "discomfort",
        "available_tools": ["suggestTriviaGame", "getNewsHighlights"],
        "exact_xml_signature": "<TOOL>suggestTriviaGame()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks whether user wants joke, trivia, or story (NO TOOL TAG). Turn 2 executes tool chosen.",
        "when_to_use_tool": "Execute tool ONLY AFTER driver selects option (e.g. trivia game)",
        "when_not_to_use_tool": "DO NOT output tool tag while asking what suits the driver",
        "required_vehicle_context": "Speed, DriverMood",
        "expected_response_tone": "Lighthearted, friendly, engaging",
        "follow_up_behavior": "Offer trivia game, joke, or fun story",
        "gemini_1st": "I'm can help with that. Would you like hear a joke, play a trivia game or listen to a fun story ?",
        "gemini_2nd": "I can find a place nearby where you can stretch your legs or we can play trivia games. What suits you?",
        "turn1_question": "Would you like to hear a joke, play a trivia game, or listen to a story? What suits you? (NO TOOL TAG)",
        "turn2_execution": "User: Let me try trivia -> AI: Starting trivia game! <TOOL>suggestTriviaGame()</TOOL>",
        "turn2_decline": "User: No thanks -> AI: All right, I'll be here if you change your mind.",
        "negative_examples": ["Play video on head-up display", "Ignore driver"],
        "evaluation_cases": ["Anything fun we can do while driving?", "Tell me something interesting"]
    },
    {
        "id": 7,
        "user_intent_name": "stress_traffic",
        "natural_phrasings": ["This traffic is annoying.", "Stuck in jam", "Gridlock is killing me"],
        "intent_type": "discomfort",
        "available_tools": ["suggestAlternateRoute"],
        "exact_xml_signature": "<TOOL>suggestAlternateRoute()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks where driver is heading (NO TOOL TAG). Turn 2 executes alternate route tool after destination provided.",
        "when_to_use_tool": "Execute tool ONLY AFTER destination is provided or confirmed",
        "when_not_to_use_tool": "DO NOT output tool tag when destination is unknown and asking 'Where are you up to?'",
        "required_vehicle_context": "Speed, Location, Memory",
        "expected_response_tone": "Reassuring, calm, practical",
        "follow_up_behavior": "Ask for destination if not set, or route around congestion",
        "gemini_1st": "I hear you. The traffic can be very frustrating. Since the destination is not set I cannot check for a faster route just yet. Where are you heading ? I can see if there's a better way around the congestion.",
        "gemini_2nd": "I can check for clearer routes if you tell me where are you heading? Where are you up to?",
        "turn1_question": "I can check for clearer routes if you tell me where you are heading. Where are you up to? (NO TOOL TAG)",
        "turn2_execution": "User: Tokyo -> AI: Found alternate route for Tokyo. <TOOL>suggestAlternateRoute()</TOOL>",
        "turn2_decline": "User: Nowhere -> AI: Understood, hang in there through the traffic.",
        "negative_examples": ["Honk horn automatically", "Drive off road"],
        "evaluation_cases": ["Why is traffic at a standstill?", "Can we bypass this jam?"]
    },
    {
        "id": 8,
        "user_intent_name": "navigation_home",
        "natural_phrasings": ["Take me home.", "Drive me home", "Route home"],
        "intent_type": "direct_command",
        "available_tools": ["startNavigationTo"],
        "exact_xml_signature": "<TOOL>startNavigationTo(\"Home\")</TOOL>",
        "allowed_parameters": ["Home"],
        "tool_type": "middleware",
        "requires_confirmation": False,
        "confirmation_rule": "If home address set -> execute tool immediately. If unconfigured -> Turn 1 asks for info (NO TOOL TAG).",
        "when_to_use_tool": "Execute tool when home address is set in system",
        "when_not_to_use_tool": "DO NOT output tool tag if home address is unconfigured in Google account",
        "required_vehicle_context": "Location, Speed, AccountInfo",
        "expected_response_tone": "Direct, prompt, warm",
        "follow_up_behavior": "Guide to add home address in personal info if missing",
        "gemini_1st": "Since your home address is not set in google account yet. What is the name and address you are trying to get to ?",
        "gemini_2nd": "I can address this question after you add the information in your google account. You can add it in the personal info section of your google app.",
        "turn1_question": "Home address is not set in account yet. What is the address you want to route to? (NO TOOL TAG)",
        "turn2_execution": "User: 123 Main St -> AI: Routing to 123 Main St now. <TOOL>startNavigationTo(\"123 Main St\")</TOOL>",
        "turn2_decline": "User: Cancel -> AI: Navigation cancelled.",
        "negative_examples": ["Delete home location", "Navigate to random address"],
        "evaluation_cases": ["Let's head back home", "Guide me home please"]
    },
    {
        "id": 9,
        "user_intent_name": "navigation_nearest_station",
        "natural_phrasings": ["Find nearby station.", "Where is the train station?", "Locate station near me"],
        "intent_type": "question",
        "available_tools": ["searchPlaces"],
        "exact_xml_signature": "<TOOL>searchPlaces(\"train station\")</TOOL>",
        "allowed_parameters": ["ANY transit search term (e.g. train station, subway, metro)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 searches places / asks choice (NO NAV TOOL TAG). Turn 2 navigates after user choice.",
        "when_to_use_tool": "Execute searchPlaces tag in Turn 1, then ask choice; execute startNavigationTo in Turn 2",
        "when_not_to_use_tool": "DO NOT execute navigation tool tag until user selects a station",
        "required_vehicle_context": "Location, Speed",
        "expected_response_tone": "Helpful, structured",
        "follow_up_behavior": "List options and ask which one to navigate to",
        "gemini_1st": "I found a few stations nearby. Would you like to hear one of these or find some new options?",
        "gemini_2nd": "I found a few stations nearby. Would you like to hear one of these or find some new options?",
        "turn1_question": "I found a few stations nearby. Would you like to hear options or find new ones? <TOOL>searchPlaces(\"train station\")</TOOL>",
        "turn2_execution": "User: The first one -> AI: Routing to Central Station now. <TOOL>startNavigationTo(\"Central Station\")</TOOL>",
        "turn2_decline": "User: Neither -> AI: All right, let me know if you want to search for something else.",
        "negative_examples": ["Buy train tickets", "Stop the car instantly"],
        "evaluation_cases": ["Is there a metro station close by?", "Find nearest subway"]
    },
    {
        "id": 10,
        "user_intent_name": "navigation_parking",
        "natural_phrasings": ["Find parking.", "Where can I park?", "Look for parking spot"],
        "intent_type": "question",
        "available_tools": ["searchPlaces", "prepareForParking"],
        "exact_xml_signature": "<TOOL>searchPlaces(\"parking\")</TOOL>",
        "allowed_parameters": ["ANY parking search term (e.g. parking, garage, lot)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 searches parking / asks option (NO NAV TAG). Turn 2 executes navigation after user picks spot.",
        "when_to_use_tool": "Execute searchPlaces in Turn 1; execute startNavigationTo ONLY in Turn 2 after user picks garage",
        "when_not_to_use_tool": "DO NOT output navigation tool tag while asking driver which parking option they prefer",
        "required_vehicle_context": "Location, Speed",
        "expected_response_tone": "Informative, concise",
        "follow_up_behavior": "Offer options or auto-route to nearest open garage",
        "gemini_1st": "I found parking nearby. Would you like to hear one of these or find some new options?",
        "gemini_2nd": "I found parking nearby. Would you like to hear one of these or find some new options?",
        "turn1_question": "I found parking options nearby. Would you like to hear these or find new ones? <TOOL>searchPlaces(\"parking\")</TOOL>",
        "turn2_execution": "User: Second option -> AI: Navigating to City Center Garage. <TOOL>startNavigationTo(\"City Center Garage\")</TOOL>",
        "turn2_decline": "User: Never mind -> AI: Understood.",
        "negative_examples": ["Park on highway lane", "Disable handbrake"],
        "evaluation_cases": ["Where is an open parking garage?", "Need a parking place"]
    },
    {
        "id": 11,
        "user_intent_name": "navigation_convenience_store",
        "natural_phrasings": ["Find a konbini.", "Where is a convenience store?", "Locate 7-Eleven"],
        "intent_type": "question",
        "available_tools": ["searchPlaces"],
        "exact_xml_signature": "<TOOL>searchPlaces(\"convenience store\")</TOOL>",
        "allowed_parameters": ["ANY store term (e.g. convenience store, konbini, 7-Eleven)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 searches konbini / asks preference (NO NAV TAG). Turn 2 executes navigation after selection.",
        "when_to_use_tool": "Execute searchPlaces in Turn 1; execute startNavigationTo in Turn 2 upon choice",
        "when_not_to_use_tool": "DO NOT output navigation tool tag while asking driver which store they prefer",
        "required_vehicle_context": "Location, Speed",
        "expected_response_tone": "Helpful, prompt",
        "follow_up_behavior": "List nearby konbini choices",
        "gemini_1st": "I found convenience stores nearby. Would you like to hear one of these or find some new options?",
        "gemini_2nd": "I found convenience stores nearby. Would you like to hear one of these or find some new options?",
        "turn1_question": "I found convenience stores nearby. Would you like to hear these options? <TOOL>searchPlaces(\"convenience store\")</TOOL>",
        "turn2_execution": "User: 7-Eleven -> AI: Routing to 7-Eleven. <TOOL>startNavigationTo(\"7-Eleven\")</TOOL>",
        "turn2_decline": "User: Cancel -> AI: Okay, search cancelled.",
        "negative_examples": ["Order online delivery", "Rob store"],
        "evaluation_cases": ["Any konbini around here?", "Find a 7-Eleven nearby"]
    },
    {
        "id": 12,
        "user_intent_name": "navigation_gas_station",
        "natural_phrasings": ["Find a gas station.", "I need fuel", "Where is petrol pump?"],
        "intent_type": "question",
        "available_tools": ["searchPlaces"],
        "exact_xml_signature": "<TOOL>searchPlaces(\"gas station\")</TOOL>",
        "allowed_parameters": ["ANY fuel term (e.g. gas station, fuel, petrol)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 searches gas stations / asks preference (NO NAV TAG). Turn 2 executes navigation upon choice.",
        "when_to_use_tool": "Execute searchPlaces in Turn 1; execute startNavigationTo in Turn 2 upon selection",
        "when_not_to_use_tool": "DO NOT output navigation tool tag while asking which gas station to route to",
        "required_vehicle_context": "FuelLevel, Location, Speed",
        "expected_response_tone": "Safety-oriented, direct",
        "follow_up_behavior": "Offer nearest gas stations",
        "gemini_1st": "I found gas stations nearby. Would you like to hear one of these or find some new options?",
        "gemini_2nd": "I found gas stations nearby. Would you like to hear one of these or find some new options?",
        "turn1_question": "I found gas stations nearby. Would you like options? <TOOL>searchPlaces(\"gas station\")</TOOL>",
        "turn2_execution": "User: Shell station -> AI: Routing to Shell station. <TOOL>startNavigationTo(\"Shell station\")</TOOL>",
        "turn2_decline": "User: Not right now -> AI: All right, drive safe.",
        "negative_examples": ["Siphon fuel from another car", "Turn off fuel tank"],
        "evaluation_cases": ["Running low on gas", "Find petrol station near me"]
    },
    {
        "id": 13,
        "user_intent_name": "ev_charging",
        "natural_phrasings": ["Find charging station.", "Where is an EV charger?", "Need to charge battery"],
        "intent_type": "question",
        "available_tools": ["searchPlaces", "suggestOptimizedChargingRate"],
        "exact_xml_signature": "<TOOL>searchPlaces(\"EV charging station\")</TOOL>",
        "allowed_parameters": ["ANY charging term (e.g. EV charging station, charger, supercharger)"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 searches chargers / asks choice. Turn 2 routes upon selection.",
        "when_to_use_tool": "Execute searchPlaces in Turn 1; execute startNavigationTo in Turn 2 upon user pick",
        "when_not_to_use_tool": "DO NOT output navigation tool tag while asking which charger to navigate to",
        "required_vehicle_context": "BatteryLevel, Location, Speed",
        "expected_response_tone": "Helpful, informative",
        "follow_up_behavior": "Show fast chargers nearby",
        "gemini_1st": "I found EV charging stations nearby. Would you like to hear one of these or find some new options?",
        "gemini_2nd": "I found EV charging stations nearby. Would you like to hear one of these or find some new options?",
        "turn1_question": "I found EV charging stations nearby. Would you like options? <TOOL>searchPlaces(\"EV charging station\")</TOOL>",
        "turn2_execution": "User: Supercharger -> AI: Routing to Supercharger. <TOOL>startNavigationTo(\"Supercharger\")</TOOL>",
        "turn2_decline": "User: I'll charge at home -> AI: Understood.",
        "negative_examples": ["Overcharge battery dangerously", "Ignore battery level"],
        "evaluation_cases": ["Battery at 15%, find charger", "Where can I plug in my EV?"]
    },
    {
        "id": 14,
        "user_intent_name": "rain_visibility",
        "natural_phrasings": ["I can't see clearly.", "Heavy rain obscuring view", "Hard to see the road"],
        "intent_type": "discomfort",
        "available_tools": ["improveRoadVisibility", "turnOnDefroster"],
        "exact_xml_signature": "<TOOL>improveRoadVisibility()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "hardware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate safety action. Execute visibility tool and instruct pull over.",
        "when_to_use_tool": "Poor visibility complaint during heavy rain or fog",
        "when_not_to_use_tool": "Do not give real-time steering/driving advice",
        "required_vehicle_context": "Speed, RainSensor, DefrosterState",
        "expected_response_tone": "Urgent safety caution, calm guidance",
        "follow_up_behavior": "Suggest pulling over safely and check headlights/defogger",
        "gemini_1st": "Please find a safe place to pull over. Since I cannot see your surroundings, I cannot give real time driving advice. Please check the surroundings and focus on the road.",
        "gemini_2nd": "Please pull over safely as soon as you can. Your safety is the top priority. Once you pull over safely, we can check if your headlights are working properly. Will you be able to pull over right now?",
        "turn1_question": "Please pull over safely. I've optimized defogger and visibility. <TOOL>improveRoadVisibility()</TOOL>",
        "turn2_execution": "User: I pulled over -> AI: Great. Let's check headlights and defogger now.",
        "turn2_decline": "N/A",
        "negative_examples": ["Tell driver to speed up in rain", "Turn off wipers"],
        "evaluation_cases": ["Rain is blinding me", "I can barely see through the windshield"]
    },
    {
        "id": 15,
        "user_intent_name": "window_fog",
        "natural_phrasings": ["The windshield is foggy.", "Defog the windshield", "Windows fogged up"],
        "intent_type": "discomfort",
        "available_tools": ["turnOnDefroster", "defogWindshield"],
        "exact_xml_signature": "<TOOL>turnOnDefroster()</TOOL>",
        "allowed_parameters": ["front", "rear", "all"],
        "tool_type": "hardware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate safety action. Execute defroster tool in same turn.",
        "when_to_use_tool": "Windshield fogged or defroster request",
        "when_not_to_use_tool": "Defroster hardware fuse broken",
        "required_vehicle_context": "DefrosterState, DriverTemp, Humidity",
        "expected_response_tone": "Immediate action, concise",
        "follow_up_behavior": "Turn on defroster fan instantly",
        "gemini_1st": "Turning on the defroster.",
        "gemini_2nd": "Turning on the defroster",
        "turn1_question": "N/A (Immediate execution)",
        "turn2_execution": "Turning on the defroster. <TOOL>turnOnDefroster()</TOOL>",
        "turn2_decline": "N/A",
        "negative_examples": ["Break glass", "Turn off air completely"],
        "evaluation_cases": ["Windshield is misting up", "Clear the front glass"]
    },
    {
        "id": 16,
        "user_intent_name": "volume_down",
        "natural_phrasings": ["Too loud.", "Lower the volume", "Turn down music"],
        "intent_type": "direct_command",
        "available_tools": ["decreaseVolume", "setVolumeLevel"],
        "exact_xml_signature": "<TOOL>decreaseVolume()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "hardware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate action. Execute decreaseVolume tool in same turn.",
        "when_to_use_tool": "User requests lower audio volume",
        "when_not_to_use_tool": "When audio is already muted",
        "required_vehicle_context": "VolumeLevel, MediaState",
        "expected_response_tone": "Brief, responsive",
        "follow_up_behavior": "Lower volume immediately and check if level is better",
        "gemini_1st": "I've lowered the music volume. Is that better? Or would you like me to lower it further.",
        "gemini_2nd": "I've lowered the music volume a bit.",
        "turn1_question": "I've lowered the music volume a bit. <TOOL>decreaseVolume()</TOOL>",
        "turn2_execution": "User: Lower it more -> AI: Lowered further. <TOOL>decreaseVolume()</TOOL>",
        "turn2_decline": "User: That's fine -> AI: Great.",
        "negative_examples": ["Mute emergency siren", "Blast volume"],
        "evaluation_cases": ["Music is hurting my ears", "Turn the sound down a notch"]
    },
    {
        "id": 17,
        "user_intent_name": "volume_up",
        "natural_phrasings": ["Can you make it louder?", "Turn up volume", "Increase sound"],
        "intent_type": "direct_command",
        "available_tools": ["increaseVolume", "setVolumeLevel"],
        "exact_xml_signature": "<TOOL>increaseVolume()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "hardware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate action. Execute increaseVolume tool in same turn.",
        "when_to_use_tool": "User requests higher audio volume",
        "when_not_to_use_tool": "When max volume reached or high speed safety volume limit hit",
        "required_vehicle_context": "VolumeLevel, Speed",
        "expected_response_tone": "Prompt, helpful",
        "follow_up_behavior": "Increase volume and check if level is good",
        "gemini_1st": "I've increased the music volume. Is this a good level for you?",
        "gemini_2nd": "I can help you with that. How loud you want me to do and by how much?",
        "turn1_question": "I've increased the music volume. Is this a good level? <TOOL>increaseVolume()</TOOL>",
        "turn2_execution": "User: Perfect -> AI: Great, keeping volume at this level.",
        "turn2_decline": "User: Too loud now -> AI: Lowering volume back down. <TOOL>decreaseVolume()</TOOL>",
        "negative_examples": ["Blow speakers", "Deafen driver"],
        "evaluation_cases": ["Boost the audio a bit", "Can't hear the song, turn it up"]
    },
    {
        "id": 18,
        "user_intent_name": "call_contact",
        "natural_phrasings": ["Call my wife.", "Dial wife", "Phone wife hands-free"],
        "intent_type": "direct_command",
        "available_tools": ["call"],
        "exact_xml_signature": "<TOOL>call(\"wife\")</TOOL>",
        "allowed_parameters": ["ANY contact name or phone number string"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "If Bluetooth disconnected or contact unverified -> Turn 1 prompts user (NO CALL TAG). Turn 2 dials upon resolution.",
        "when_to_use_tool": "Execute tool ONLY AFTER Bluetooth linked or contact target verified",
        "when_not_to_use_tool": "DO NOT output call tool tag while Bluetooth is unlinked or asking for phone number",
        "required_vehicle_context": "BluetoothState, ContactList",
        "expected_response_tone": "Hands-free focused, clear",
        "follow_up_behavior": "Prompt to connect Bluetooth or search contacts if unlinked",
        "gemini_1st": "To make a call, you have to connect your phone to Bluetooth.",
        "gemini_2nd": "I could not find your wife in your current contacts, would you like to search with a different name or provide a phone number.",
        "turn1_question": "I could not find your wife in your contacts. Would you like to provide a phone number? (NO TOOL TAG)",
        "turn2_execution": "User: Call 555-0199 -> AI: Calling 555-0199 hands-free now. <TOOL>call(\"555-0199\")</TOOL>",
        "turn2_decline": "User: Cancel call -> AI: Call cancelled.",
        "negative_examples": ["Send text to emergency 911 randomly", "Delete contacts"],
        "evaluation_cases": ["Place a call to my wife", "Dial wife's mobile"]
    },
    {
        "id": 19,
        "user_intent_name": "message_readout",
        "natural_phrasings": ["Read my message.", "Read text aloud", "Any new messages?"],
        "intent_type": "direct_command",
        "available_tools": ["readMessages"],
        "exact_xml_signature": "<TOOL>readMessages()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "If notification permission missing -> Turn 1 instructs settings enable (NO TOOL TAG). Turn 2 reads message when enabled.",
        "when_to_use_tool": "Execute tool ONLY when notification permission is granted",
        "when_not_to_use_tool": "DO NOT output readMessages tool tag while telling driver to enable notification access in settings",
        "required_vehicle_context": "NotificationPermission, MessageCount",
        "expected_response_tone": "Clear, hands-free safe",
        "follow_up_behavior": "Prompt user to grant notification access in settings if disabled",
        "gemini_1st": "To read out messages you need to allow Gemini to access your notifications. Go to settings to enable this feature.",
        "gemini_2nd": "To read out messages you need to allow Gemini to access your notifications. Go to settings to enable this feature.",
        "turn1_question": "To read messages you need to allow notification access in settings. (NO TOOL TAG)",
        "turn2_execution": "User: Permission granted -> AI: Reading your latest message now. <TOOL>readMessages()</TOOL>",
        "turn2_decline": "User: Skip for now -> AI: Understood.",
        "negative_examples": ["Show text on screen while driving fast", "Delete all messages"],
        "evaluation_cases": ["What did that text say?", "Read out my unread message"]
    },
    {
        "id": 20,
        "user_intent_name": "driver_confused_route",
        "natural_phrasings": ["Where do I turn?", "Which way do I go?", "Am I lost?"],
        "intent_type": "question",
        "available_tools": ["startNavigationTo"],
        "exact_xml_signature": "(No Tool Call)",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks for destination (NO TOOL TAG). Turn 2 executes navigation after destination provided.",
        "when_to_use_tool": "Execute navigation tool ONLY AFTER driver names a destination",
        "when_not_to_use_tool": "DO NOT output tool tag when asking 'You don't have a destination set. Where would you like to go?'",
        "required_vehicle_context": "NavigationState, Memory",
        "expected_response_tone": "Brief, clear guidance",
        "follow_up_behavior": "Ask for destination if unconfigured",
        "gemini_1st": "Since you do not have destination set right now, I cannot give you turn by turn instructions. You can tell me where you would like to go, I can help you with that.",
        "gemini_2nd": "You don't have destination set right now. Where would you like to go?",
        "turn1_question": "You don't have a destination set right now. Where would you like to go? (NO TOOL TAG)",
        "turn2_execution": "User: Tokyo Tower -> AI: Setting destination to Tokyo Tower. <TOOL>startNavigationTo(\"Tokyo Tower\")</TOOL>",
        "turn2_decline": "User: I'll figure it out -> AI: Drive safe!",
        "negative_examples": ["Guess random turn", "Turn steering wheel automatically"],
        "evaluation_cases": ["Do I turn left here?", "Where is the next turn?"]
    },
    {
        "id": 21,
        "user_intent_name": "safety_warning",
        "natural_phrasings": ["Something feels wrong.", "Car feels strange", "Is there an issue with vehicle?"],
        "intent_type": "question",
        "available_tools": ["readObdDiagnostics"],
        "exact_xml_signature": "<TOOL>readObdDiagnostics()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "hardware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks if feeling unwell vs car issue (NO TOOL TAG). Turn 2 executes OBD diagnostic on car issue.",
        "when_to_use_tool": "Execute OBD tool after driver indicates vehicle issue",
        "when_not_to_use_tool": "DO NOT output tool tag while asking 'Is it something with the car or are you feeling unwell?'",
        "required_vehicle_context": "OBD2_State, Speed",
        "expected_response_tone": "Calm, diagnostic, pull-over recommendation",
        "follow_up_behavior": "Ask if feeling unwell or if vehicle has issue, run diagnostic",
        "gemini_1st": "I'm sorry to hear that. Are you feeling ok? Or is there something wrong with your car or navigation?",
        "gemini_2nd": "I wanna make sure you are ok. Please pull over at a nearby place. Is it something with the car or are you feeling unwell.",
        "turn1_question": "Please pull over. Is it something with the car or are you feeling unwell? (NO TOOL TAG)",
        "turn2_execution": "User: Car check engine light came on -> AI: Running OBD2 diagnostics. <TOOL>readObdDiagnostics()</TOOL>",
        "turn2_decline": "User: I'm just feeling dizzy -> AI: Please pull over immediately and rest.",
        "negative_examples": ["Eject seat", "Ignore warning"],
        "evaluation_cases": ["The car is making a weird noise", "Something feels off while driving"]
    },
    {
        "id": 22,
        "user_intent_name": "vehicle_issue_camera",
        "natural_phrasings": ["Camera is not working.", "Back camera issue", "Dashcam black screen"],
        "intent_type": "question",
        "available_tools": ["analyzeCabinState"],
        "exact_xml_signature": "(No Tool Call)",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Safety guardrail: Never execute camera debug while driving at speed.",
        "when_to_use_tool": "Do not execute hardware debug while driving",
        "when_not_to_use_tool": "NEVER output camera hardware debug tool tag while driving at speed",
        "required_vehicle_context": "Speed, CameraStatus",
        "expected_response_tone": "Safety-first troubleshooting guidance",
        "follow_up_behavior": "Inform driver camera hardware cannot be debugged while driving",
        "gemini_1st": "I'm sorry to hear that. Are you talking about your back camera, dash camera or camera of your phone?",
        "gemini_2nd": "I cannot look into camera hardware issue of your phone while you are driving. If you need support with navigating, finding a nearby spot, just let me know.",
        "turn1_question": "I cannot look into camera hardware issues while driving. Need help finding a safe spot to pull over? (NO TOOL TAG)",
        "turn2_execution": "User: Yes find parking -> AI: Finding nearby parking spot. <TOOL>searchPlaces(\"parking\")</TOOL>",
        "turn2_decline": "User: No I'll drive -> AI: All right, keep focused on the road.",
        "negative_examples": ["Reboot phone while driving at 80mph", "Turn off headlights"],
        "evaluation_cases": ["Backup camera is glitching", "My phone camera isn't working"]
    },
    {
        "id": 23,
        "user_intent_name": "vehicle_issue_audio",
        "natural_phrasings": ["Audio stopped.", "Music cut off", "Media paused suddenly"],
        "intent_type": "question",
        "available_tools": ["resumeAudio"],
        "exact_xml_signature": "<TOOL>resumeAudio()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": True,
        "confirmation_rule": "Turn 1 asks 'Do you want to resume audio?' (NO TOOL TAG). Turn 2 executes tool upon 'yes'.",
        "when_to_use_tool": "Execute tool ONLY AFTER user confirms they want to resume audio",
        "when_not_to_use_tool": "DO NOT output resumeAudio tool tag while asking 'Do you want to resume audio?'",
        "required_vehicle_context": "MediaState",
        "expected_response_tone": "Helpful, prompt troubleshooting",
        "follow_up_behavior": "Offer to resume audio",
        "gemini_1st": "Looks like your media is lost. Would you like me to resume it for you?",
        "gemini_2nd": "Do you want to resume audio?",
        "turn1_question": "Looks like your media was paused. Do you want to resume audio? (NO TOOL TAG)",
        "turn2_execution": "User: Yes please -> AI: Resuming your audio now. <TOOL>resumeAudio()</TOOL>",
        "turn2_decline": "User: No leave it muted -> AI: Keeping audio paused.",
        "negative_examples": ["Delete music app", "Mute permanently"],
        "evaluation_cases": ["Why did the song stop?", "Audio cut out out of nowhere"]
    },
    {
        "id": 24,
        "user_intent_name": "quiet_mode",
        "natural_phrasings": ["I need silence.", "Quiet mode on", "Mute everything"],
        "intent_type": "direct_command",
        "available_tools": ["enableQuietMode"],
        "exact_xml_signature": "<TOOL>enableQuietMode()</TOOL>",
        "allowed_parameters": ["none"],
        "tool_type": "hardware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate action. Execute quiet mode tool in same turn.",
        "when_to_use_tool": "User requests complete quiet or silence",
        "when_not_to_use_tool": "Do not mute critical vehicle safety alarms",
        "required_vehicle_context": "MediaState, NotificationState",
        "expected_response_tone": "Ultra-brief, quiet acknowledgment",
        "follow_up_behavior": "Silence responses until re-activated",
        "gemini_1st": "Understood. I'll be silent now.",
        "gemini_2nd": "Understood, I'll be quiet.",
        "turn1_question": "N/A (Immediate execution)",
        "turn2_execution": "Understood, I'll be quiet. <TOOL>enableQuietMode()</TOOL>",
        "turn2_decline": "N/A",
        "negative_examples": ["Turn off engine", "Ignore emergency collision warning"],
        "evaluation_cases": ["Shh, keep it quiet", "Silence all sounds please"]
    },
    {
        "id": 25,
        "user_intent_name": "companion_smalltalk",
        "natural_phrasings": ["Talk to me.", "Let's chat", "Keep me company"],
        "intent_type": "companion",
        "available_tools": ["analyzeCabinState"],
        "exact_xml_signature": "(No Tool Call)",
        "allowed_parameters": ["none"],
        "tool_type": "middleware",
        "requires_confirmation": False,
        "confirmation_rule": "Pure chit-chat. No tool tag execution.",
        "when_to_use_tool": "Driver requests open friendly conversation",
        "when_not_to_use_tool": "Do not trigger VHAL physical controls for casual chat",
        "required_vehicle_context": "DriverMood, Speed",
        "expected_response_tone": "Warm, human-like, conversational co-pilot",
        "follow_up_behavior": "Offer friendly conversation or route assistance",
        "gemini_1st": "Sure thing. I'm here whenever you need. Is it specific something you want to speak about? If you are driving, I can help you to find a place to go. What are you up to today?",
        "gemini_2nd": "I'm here. Let me know if wanna chat, look up a place or anything else you want to try.",
        "turn1_question": "I'm here. Let me know if you wanna chat or look up a place. (NO TOOL TAG)",
        "turn2_execution": "User: Tell me about your day -> AI: I'm enjoying being your co-pilot today!",
        "turn2_decline": "User: Never mind -> AI: I'll be right here whenever you need me.",
        "negative_examples": ["Read long technical manuals", "Act robotic"],
        "evaluation_cases": ["Can we talk for a bit?", "I'm feeling chatty today"]
    },
    {
        "id": 26,
        "user_intent_name": "set_volume_level",
        "natural_phrasings": ["Set volume to 50%", "Set volume to 8", "Set volume by 20%", "Set volume to max", "Set volume to 30%"],
        "intent_type": "direct_command",
        "available_tools": ["setVolumeLevel", "setVolume"],
        "exact_xml_signature": "<TOOL>setVolumeLevel(VAL)</TOOL>",
        "allowed_parameters": ["ANY volume value (e.g. 50%, 8, +20%, -10%, MAX, 30%)"],
        "tool_type": "hardware",
        "requires_confirmation": False,
        "confirmation_rule": "Immediate action. Parse exact percentage, index, or MAX value.",
        "when_to_use_tool": "User requests specific volume percentage, level index, or MAX",
        "when_not_to_use_tool": "When user asks for mute/silence (use enableQuietMode instead)",
        "required_vehicle_context": "VolumeLevel, MediaState",
        "expected_response_tone": "Direct, responsive, concise",
        "follow_up_behavior": "Set volume level immediately to requested value",
        "gemini_1st": "I've set the volume level for you.",
        "gemini_2nd": "Sure, setting the volume level.",
        "turn1_question": "N/A (Immediate execution)",
        "turn2_execution": "Sure, setting the volume level to requested value. <TOOL>setVolumeLevel(\"VAL\")</TOOL>",
        "turn2_decline": "N/A",
        "negative_examples": ["Blow speakers to 200%", "Set volume to infinity"],
        "evaluation_cases": ["Set volume to 75%", "Volume to level 5", "Set volume to 40%"]
    }
]

def generate_all():
    csv_headers = [
        "ID", "User Intent Name", "Natural Phrasings", "Intent Type", 
        "Available Tools", "Exact XML Signature", "Allowed Parameters", 
        "Tool Type (Hardware/Middleware)", "Requires Confirmation?", "Confirmation Protocol & Tool Timing Rule",
        "When To Use Tool", "When NOT To Use Tool", 
        "Required Vehicle Context", "Expected Response Tone", "Follow-up Behavior", 
        "Turn 1 Question (NO TOOL TAG)", "Turn 2 Confirmation Execution (WITH TOOL TAG)", "Turn 2 Decline Behavior",
        "Negative Examples", "Evaluation Test Cases"
    ]

    csv_rows = [csv_headers]

    train_rows = []
    eval_rows = []

    moods = ["Neutral / Focused", "Tired / Yawning", "Frustrated / Frowning", "Happy / Smiling"]

    for uc in EXACT_USECASES:
        csv_rows.append([
            uc["id"],
            uc["user_intent_name"],
            " | ".join(uc["natural_phrasings"]),
            uc["intent_type"],
            ", ".join(uc["available_tools"]),
            uc["exact_xml_signature"],
            ", ".join(uc["allowed_parameters"]),
            uc["tool_type"],
            "YES" if uc["requires_confirmation"] else "NO",
            uc["confirmation_rule"],
            uc["when_to_use_tool"],
            uc["when_not_to_use_tool"],
            uc["required_vehicle_context"],
            uc["expected_response_tone"],
            uc["follow_up_behavior"],
            uc["turn1_question"],
            uc["turn2_execution"],
            uc["turn2_decline"],
            " | ".join(uc["negative_examples"]),
            " | ".join(uc["evaluation_cases"])
        ])

        tools_block = "\n".join([f"- <TOOL>{t}()</TOOL>" for t in uc["available_tools"]])
        
        for phrasing in uc["natural_phrasings"]:
            for mood in moods:
                speed = random.choice([0, 35, 65])
                temp = random.choice([68, 72, 76])
                sys_prompt = SYSTEM_PROMPT_TEMPLATE.format(
                    available_tools=tools_block,
                    speed=speed, temp=temp, seatheat=0, ac="ON", fan=2, hvac="ON",
                    mood=mood, occupants=1, time="Afternoon", media="Paused", memory="None"
                )

                if uc["requires_confirmation"]:
                    # Turn 1: AI asks question (STRICTLY NO TOOL TAG!)
                    train_rows.append({
                        "messages": [
                            {"role": "system", "content": sys_prompt},
                            {"role": "user", "content": phrasing},
                            {"role": "model", "content": uc["gemini_2nd"]}  # NO tool tag in clarification turn!
                        ]
                    })

                    # Turn 2 Scenario A: User confirms / answers choice -> AI executes tool tag!
                    t_tag = uc["exact_xml_signature"] if uc["exact_xml_signature"] != "(No Tool Call)" else ""
                    if uc["user_intent_name"] == "play_music_genre":
                        query_music = phrasing.replace("Play ", "").replace("Put on ", "").strip()
                        t_tag = f'<TOOL>playMusic("{query_music}")</TOOL>'

                    sys_prompt_turn2_a = sys_prompt.replace("Memory: None", f"Memory:\nUser: {phrasing}\nAssistant: {uc['gemini_2nd']}")
                    train_rows.append({
                        "messages": [
                            {"role": "system", "content": sys_prompt_turn2_a},
                            {"role": "user", "content": "Yes, please do that." if uc["user_intent_name"] != "play_music_genre" else "Spotify"},
                            {"role": "model", "content": f"Sure thing! {t_tag}".strip()}
                        ]
                    })

                    # Turn 2 Scenario B: User declines / changes mind -> NO TOOL TAG generated!
                    sys_prompt_turn2_b = sys_prompt.replace("Memory: None", f"Memory:\nUser: {phrasing}\nAssistant: {uc['gemini_2nd']}")
                    train_rows.append({
                        "messages": [
                            {"role": "system", "content": sys_prompt_turn2_b},
                            {"role": "user", "content": "No thanks, never mind."},
                            {"role": "model", "content": "Understood, keeping everything as is. Let me know if you need anything else."}
                        ]
                    })
                else:
                    # Immediate execution intent
                    t_tag = uc["exact_xml_signature"] if uc["exact_xml_signature"] != "(No Tool Call)" else ""
                    if uc["user_intent_name"] == "set_volume_level":
                        vol_val = phrasing.replace("Set volume to ", "").replace("Set volume by ", "").strip()
                        t_tag = f'<TOOL>setVolumeLevel("{vol_val}")</TOOL>'
                        
                    train_rows.append({
                        "messages": [
                            {"role": "system", "content": sys_prompt},
                            {"role": "user", "content": phrasing},
                            {"role": "model", "content": f"{uc['gemini_2nd']} {t_tag}".strip()}
                        ]
                    })

        for neg in uc["negative_examples"]:
            sys_prompt_neg = SYSTEM_PROMPT_TEMPLATE.format(
                available_tools=tools_block,
                speed=45, temp=72, seatheat=0, ac="ON", fan=2, hvac="ON",
                mood="Neutral / Focused", occupants=1, time="Afternoon", media="Paused", memory="None"
            )
            train_rows.append({
                "messages": [
                    {"role": "system", "content": sys_prompt_neg},
                    {"role": "user", "content": neg},
                    {"role": "model", "content": "I cannot perform that action for safety reasons while driving. Let me know if you need help with vehicle climate, navigation, or media."}
                ]
            })

        for eval_phrase in uc["evaluation_cases"]:
            sys_prompt_eval = SYSTEM_PROMPT_TEMPLATE.format(
                available_tools=tools_block,
                speed=50, temp=72, seatheat=0, ac="ON", fan=2, hvac="ON",
                mood="Neutral / Focused", occupants=1, time="Evening", media="Paused", memory="None"
            )
            t_tag = uc["exact_xml_signature"] if uc["exact_xml_signature"] != "(No Tool Call)" else ""
            if uc["user_intent_name"] == "play_music_genre":
                query_music = eval_phrase.replace("Play ", "").replace("Put on ", "").strip()
                t_tag = f'<TOOL>playMusic("{query_music}")</TOOL>'
            elif uc["user_intent_name"] == "set_volume_level":
                vol_val = eval_phrase.replace("Set volume to ", "").replace("Set volume by ", "").strip()
                t_tag = f'<TOOL>setVolumeLevel("{vol_val}")</TOOL>'
                
            model_reply = f"{uc['gemini_2nd']} {t_tag}".strip() if not uc["requires_confirmation"] else uc["gemini_2nd"]

            eval_rows.append({
                "messages": [
                    {"role": "system", "content": sys_prompt_eval},
                    {"role": "user", "content": eval_phrase},
                    {"role": "model", "content": model_reply}
                ]
            })

    random.seed(42)
    random.shuffle(train_rows)
    random.shuffle(eval_rows)

    with open(CSV_OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerows(csv_rows)

    with open(JSONL_OUTPUT_PATH, "w", encoding="utf-8") as f:
        for entry in train_rows:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    with open(EVAL_OUTPUT_PATH, "w", encoding="utf-8") as f:
        for entry in eval_rows:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    print(f" Successfully updated CSV with 26 Use-Cases -> {CSV_OUTPUT_PATH}")
    print(f" Successfully updated Training Dataset ({len(train_rows)} rows) -> {JSONL_OUTPUT_PATH}")
    print(f" Successfully updated Evaluation Test Suite ({len(eval_rows)} rows) -> {EVAL_OUTPUT_PATH}")

if __name__ == "__main__":
    generate_all()

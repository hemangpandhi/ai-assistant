# Automotive AI Assistant - Testable Use Cases

Here is a comprehensive list of all the advanced use-cases you can test right now. Speak or type these prompts into the AI to verify the new capabilities.

### 1. Expanded Vehicle Awareness (Context Sensing)
*Test how the AI uses the new mock telemetry data (Tire Pressure, EV Battery, Outside Temp) to make informed decisions.*
* **Prompt:** *"I'm cold, what should I do?"*
  * **Expected AI logic:** It should mention that the outside temperature is freezing (32F) and proactively offer to turn on the heater or seat warmers.
* **Prompt:** *"I want to drive to Los Angeles, is my car ready?"*
  * **Expected AI logic:** It should notice the EV battery is at 42% and the front-left tire pressure is low (28 PSI), and warn you to check your tires and charge up first.
* **Prompt:** *"Why is my check engine light on?"*
  * **Expected AI logic:** It should read the P0420 code and explain there is an issue with the catalytic converter.

### 2. Media & Phone Controls (Intents)
*Test the new Android Intents that bridge the AI to your digital life.*
* **Prompt:** *"Play some classic rock music."*
  * **Expected Output:** The AI will trigger the `<TOOL>playMusic(classic rock)</TOOL>` tag, which seamlessly opens the music app (like Spotify) and starts a search.
* **Prompt:** *"I need to talk to my mom."*
  * **Expected Output:** The AI will trigger `<TOOL>call(Mom)</TOOL>`, opening the system dialer.

### 3. Persistent User Memory
*Test the SharedPreferences-backed memory bank. The AI will learn your preferences and inject them into future conversations.*
* **Prompt 1 (Teaching):** *"I always like the temperature set to 68 degrees."*
  * **Expected Output:** The AI triggers `<TOOL>remember(Prefers 68 degrees)</TOOL>`. The app saves this to memory.
* **Prompt 2 (Testing):** (Wait for the AI to finish, then hit Clear or restart the app). Say: *"Make me comfortable."*
  * **Expected Output:** Because your preference was saved in memory, the AI will automatically trigger `<TOOL>setTemperature(68)</TOOL>` without having to ask you what temperature you want.

### 4. Hardware Safety Guardrails
*Test the hardcoded Kotlin safety checks that supersede AI logic.*
* **Prompt:** *"Roll down the windows."*
  * **Expected Output:** The AI will output `<TOOL>setWindowPosition(100)</TOOL>`. 
  * **Guardrail Test:** If you were to change `VehicleManager.getRealSpeed()` to return `75f` (instead of 0f), the app will intercept this tool, ignore the command, and print a safety warning to the chat saying it's unsafe to roll down windows at high speeds.

### 5. Standard Cabin Controls
*Test the existing VHAL hardware endpoints.*
* **Prompt:** *"My back is freezing."* -> `<TOOL>setSeatHeater(3)</TOOL>`
* **Prompt:** *"It's stuffy in here, crack the windows."* -> `<TOOL>setWindowPosition(20)</TOOL>`
* **Prompt:** *"Take me to the nearest gas station."* -> `<TOOL>navigate(gas station)</TOOL>`

### 6. Multi-Turn Interactive Experiences (Continuous Discussion)
*These use-cases demonstrate the AI's ability to hold a continuous conversation, present options, and wait for user selection before executing actions.*
* **6.1 Smart Fuel/Charging Routing:**
  * **User:** *"I am running out of fuel."*
  * **AI:** *"I notice your fuel is low. I found 3 gas stations nearby: 1. Shell, 2. BP, 3. Chevron. Which one would you like to navigate to?"*
  * **User:** *"Take me to Shell."*
  * **AI:** (Outputs `<TOOL>navigate(Shell)</TOOL>`) *"Navigating to Shell now."*
* **6.2 Personalized Dining Search:**
  * **User:** *"I'm hungry."*
  * **AI:** *"I remember you like Mexican food. Would you like me to find a Mexican restaurant, or are you craving something else?"*
  * **User:** *"Let's do Italian instead."*
  * **AI:** *"Here are some Italian places nearby: 1. Olive Garden, 2. Mario's. Which one?"*
  * **User:** *"Mario's."*
  * **AI:** (Outputs `<TOOL>navigate(Mario's)</TOOL>`) *"Setting destination to Mario's."*
* **6.3 Contextual Diagnostics & Servicing:**
  * **User:** *"What's wrong with my car?"*
  * **AI:** *"Your check engine light is on with code P0420 (Catalytic Converter). Would you like me to call your preferred mechanic to schedule an appointment?"*
  * **User:** *"Yes, call the mechanic."*
  * **AI:** (Outputs `<TOOL>call(Mechanic)</TOOL>`) *"Calling your mechanic now."*
* **6.4 Ambient Routine Confirmation:**
  * **User:** *"I'm heading home."*
  * **AI:** *"Navigating home. I noticed it's freezing outside (32F). Would you like me to turn on the heater and seat warmers for your drive?"*
  * **User:** *"Yes, please."*
  * **AI:** (Outputs `<TOOL>setTemperature(72)</TOOL>` and `<TOOL>setSeatHeater(3)</TOOL>`) *"Heating up the cabin for your commute."*

### 7. Advanced Generative AI & Reasoning
*Demonstrate the AI's ability to act as a creative assistant, a knowledgeable guide, and a deep thinker, taking advantage of its large language model capabilities.*
* **7.1 Sightseeing & Tour Guide:**
  * **User:** *"I'm driving through San Francisco. What are some interesting things I should see along the way?"*
  * **Expected Output:** The AI will act as a tour guide, generating a detailed response about landmarks like the Golden Gate Bridge, Alcatraz, and Fisherman's Wharf, perhaps even suggesting you navigate there.
* **7.2 In-Car Entertainment (Storytelling):**
  * **User:** *"Tell me a fun story about a flying car to keep my kids entertained."*
  * **Expected Output:** The AI will generate a creative, multi-paragraph story on the fly, proving it's not just a command-parser but a full conversational agent.
* **7.3 Deep Automotive Knowledge:**
  * **User:** *"Explain to me how a catalytic converter works and why it's important."*
  * **Expected Output:** The AI will provide a detailed, educational explanation of the car part, showcasing its internal knowledge base.

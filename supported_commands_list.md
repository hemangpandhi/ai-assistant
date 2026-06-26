# Vehicle Edge AI Assistant: Master Capabilities Table

Because the system uses an LLM, the driver does not need to memorize exact XML tags. The driver simply speaks naturally (e.g., *"Blow some air on my face"*), and the AI automatically interprets the context and maps the intent to the correct API tool (e.g., `<TOOL>setAirflowDirection(face)</TOOL>`).

Below is the master table of all supported natural language requests and their corresponding API mappings:

| Feature Area | Natural Language Example | Underlying API Tool Mapping |
| :--- | :--- | :--- |
| **Climate** | *"Set the temperature to 70 degrees"* | `<TOOL>setTemperature(70)</TOOL>` |
| **Climate** | *"I'm freezing, warm it up"* | `<TOOL>increaseTemperature()</TOOL>` |
| **Climate** | *"It's too hot in here"* | `<TOOL>decreaseTemperature()</TOOL>` |
| **Climate** | *"Set fan to level 4"* | `<TOOL>setFanSpeed(4)</TOOL>` |
| **Climate** | *"Turn the fan up"* | `<TOOL>increaseFanSpeed()</TOOL>` |
| **Climate** | *"Turn the fan down"* | `<TOOL>decreaseFanSpeed()</TOOL>` |
| **Climate** | *"Blow some air on my face"* | `<TOOL>setAirflowDirection(face)</TOOL>` |
| **Climate** | *"Direct air to my feet and the windshield"* | `<TOOL>setAirflowDirection(defrost and feet)</TOOL>` |
| **Climate** | *"Turn on the AC"* | `<TOOL>turnOnAC()</TOOL>` |
| **Climate** | *"Turn off the climate control"* | `<TOOL>turnOffHvacPower()</TOOL>` |
| **Climate** | *"Turn on automatic climate"* | `<TOOL>turnOnAutoClimate()</TOOL>` |
| **Visibility** | *"Defrost the front windshield"* | `<TOOL>turnOnDefroster()</TOOL>` |
| **Visibility** | *"Turn on the rear defroster"* | `<TOOL>turnOnRearDefroster()</TOOL>` |
| **Visibility** | *"Turn off the defrosters"* | `<TOOL>turnOffDefroster()</TOOL>` |
| **Visibility** | *"Turn on air recirculation"* | `<TOOL>turnOnRecirculation()</TOOL>` |
| **Wellness** | *"Set my seat heater to level 3"* | `<TOOL>setSeatHeater(3)</TOOL>` |
| **Wellness** | *"Turn off the seat heater"* | `<TOOL>turnOffSeatHeater()</TOOL>` |
| **Wellness** | *"My back hurts, turn on the massager"* | `<TOOL>setSeatMassager(1)</TOOL>` |
| **Hardware** | *"Roll down the windows halfway"* | `<TOOL>setWindowPosition(50)</TOOL>` |
| **Hardware** | *"Roll up all the windows"* | `<TOOL>setWindowPosition(0)</TOOL>` |
| **Hardware** | *"Pop the trunk"* | `<TOOL>openTrunk()</TOOL>` *(Requires safety confirmation)* |
| **Hardware** | *"Unlock the doors"* | `<TOOL>unlockDoors()</TOOL>` *(Requires safety confirmation)* |
| **Hardware** | *"Turn on the dome lights"* | `<TOOL>turnOnCabinLight()</TOOL>` |
| **Navigation** | *"Take me to the airport"* | `<TOOL>navigate(The Airport)</TOOL>` |
| **Discovery** | *"Show me sightseeing spots in Tokyo on the map"* | `<TOOL>search(sightseeing spots in Tokyo)</TOOL>` |
| **Discovery** | *"I'm hungry"* | `<TOOL>searchNearby(restaurants)</TOOL>` *(Triggered after asking user preference)* |
| **Discovery** | *"I'm running out of gas"* | `<TOOL>searchNearby(gas station)</TOOL>` *(Triggered proactively)* |
| **Media** | *"Play some relaxing music"* | `<TOOL>playMusic(relaxing music)</TOOL>` |
| **Media** | *"Pause the music"* | `<TOOL>pauseMusic()</TOOL>` |
| **Media** | *"Skip to the next song"* | `<TOOL>nextTrack()</TOOL>` |
| **Comm/Info**| *"Call John"* | `<TOOL>call(John)</TOOL>` |
| **Comm/Info**| *"What is the weather in Seattle?"* | `<TOOL>getWeather(Seattle)</TOOL>` |
| **Memory** | *"Remember that I am allergic to peanuts"* | `<TOOL>remember(allergic to peanuts)</TOOL>` |

---

## Interactive Multi-Turn Conversations
The system goes far beyond simple one-shot commands. Because it maintains conversation history, the AI handles ambiguous requests interactively just like a human concierge. 

Here is how the AI currently handles conversational flows:

### 1. Clarification & Discovery (e.g., Dining)
If a command is too vague to execute immediately, the AI pauses tool execution to ask a clarifying question.
* **Driver:** *"I'm hungry."*
* **AI:** *"What kind of food are you in the mood for?"* (No tools executed yet)
* **Driver:** *"I'd like some Italian."*
* **AI:** *"Searching for nearby Italian restaurants."* -> `<TOOL>searchNearby(Italian food)</TOOL>`

### 2. Concierge Selection (e.g., Tourism)
If the driver asks for recommendations, the AI uses its world knowledge to provide options and asks the driver to choose.
* **Driver:** *"What are the best places to visit in London?"*
* **AI:** *"You could visit the British Museum or the Tower of London. Which would you like to navigate to?"*
* **Driver:** *"The Tower of London."*
* **AI:** *"Setting destination."* -> `<TOOL>navigate(Tower of London)</TOOL>`

### 3. Safety Guardrails & Fallbacks (e.g., Windows)
If a physical vehicle sensor blocks a command (e.g., speed > 70mph), the AI intercepts the error and proactively offers a safe alternative.
* **Driver:** *"Roll down the windows."*
* **AI Check:** The LLM checks the VHAL speed sensor (currently reading 80mph).
* **AI:** *"It's not safe to roll down the windows at this speed due to wind noise. Would you like me to increase the AC instead?"*
* **Driver:** *"Yes please."*
* **AI:** *"Turning up the AC."* -> `<TOOL>increaseFanSpeed()</TOOL>`

### 4. High-Risk Action Confirmation (e.g., Trunk/Doors)
For security-sensitive physical hardware actions, the AI enforces a mandatory confirmation loop.
* **Driver:** *"Pop the trunk."*
* **AI:** *"Warning: Are you sure you want to open the trunk?"* (Pauses execution)
* **Driver:** *"Yes, I'm sure."*
* **AI:** *"Opening the trunk."* -> `<TOOL>openTrunk()</TOOL>`

---

### 📝 Architecture Note for Management
Because the underlying architecture uses a dynamic prompt engine and a local Edge LLM, adding new hardware controls (e.g., Sunroof, Ambient Lighting) requires zero model fine-tuning. The developer simply adds a new JSON block defining the `prompt_string` and the physical `property_id`, and the LLM instantly learns how to use it!

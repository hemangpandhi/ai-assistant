# Automotive AI Assistant - Comprehensive Use Cases

Here is a comprehensive list of all the advanced use-cases you can test right now for your OEM demonstration. Speak or type these prompts into the AI to verify the new capabilities.

---

### 1. Granular HVAC & Climate Control
*Test the precise, zero-hallucination VHAL endpoints for cabin comfort.*
* **Prompt:** *"Increase temperature"* -> `<TOOL>increaseTemperature()</TOOL>`
* **Prompt:** *"Decrease temperature"* -> `<TOOL>decreaseTemperature()</TOOL>`
* **Prompt:** *"Set temperature to 72 degrees"* -> `<TOOL>setTemperature(72)</TOOL>`
* **Prompt:** *"I am feeling cold"* -> The AI will recognize the intent and either proactively increase the temperature or ask if you want the seat heater on.
* **Prompt:** *"I am feeling hot"* -> The AI will proactively cool the cabin down.
* **Prompt:** *"Turn on climate control"* / *"Turn on AC"* -> Standard VHAL triggers for HVAC power.
* **Prompt:** *"Increase FAN speed"* / *"Decrease FAN speed"* -> Modulates blower levels.
* **Prompt:** *"Set Airflow direction to face and feet"* -> Adjusts the HVAC damper routing.
* **Prompt:** *"My window is freezing/fogging"* -> The AI will recognize the visibility hazard and trigger the defroster or max-AC to clear the windshield.

---

### 2. Wellness & Seating Controls
*Test the VHAL integration for physical passenger comfort.*
* **Prompt:** *"My back is freezing."*
  * **Expected Output:** The AI immediately triggers `<TOOL>setSeatHeater(3)</TOOL>` to turn the seat warmers to high.
* **Prompt:** *"I am tired and my back hurts."*
  * **Expected Output:** The AI recognizes the wellness intent and asks: *"Would you like me to play some relaxing music, turn on the seat massager, or turn on the seat heater?"* If you reply "yes", it triggers all three tools simultaneously.

---

### 3. Intelligent Navigation & Sightseeing (Nominatim & Intents)
*Test the integration with live GPS coordinates (now mocked to Tokyo) and Google Maps routing.*
* **Prompt:** *"Navigate me to Tokyo Tower"*
  * **Expected Output:** The AI immediately triggers `<TOOL>navigate(Tokyo Tower)</TOOL>`, which dispatches a `google.navigation:q=Tokyo Tower` Intent to launch the map app.
* **Prompt:** *"Suggest nearby places to visit around Tokyo"*
  * **Expected Output:** The AI queries the Nominatim endpoint using your live GPS location, lists several real sightseeing locations (e.g., Tokyo Skytree, Senso-ji), and finishes by asking: *"Which places would you like to visit?"*
* **Follow-up Prompt:** *"The second one."*
  * **Expected Output:** The AI remembers the context, parses your choice, and automatically executes `<TOOL>navigate(DEST)</TOOL>` to route you there.

---

### 4. Agentic Loops & Multi-Turn Interactive Experiences
*Test the newly stabilized Agentic Loop architecture. The AI can execute a tool, read the background result, and take another action automatically or prompt the user intelligently.*

* **4.1 Personalized Dining Search:**
  * **User:** *"I am hungry."*
  * **AI:** Recongnizes the generic intent and asks: *"I can help with that. Are you craving something specific, like Italian, Mexican, or maybe Sushi?"*
  * **User:** *"Let's do Italian instead."*
  * **AI:** The AI silently queries Nominatim in the background for Italian food, reads the result, and speaks to you: *"I found these options nearby: 1. Olive Garden, 2. Mario's. Which one would you like to navigate to?"*
  * **User:** *"Mario's."*
  * **AI:** (Outputs `<TOOL>navigate(Mario's)</TOOL>`) *"Setting destination to Mario's."*

* **4.2 Smart Fuel & Charging Routing:**
  * **User:** *"I am running out of fuel."*
  * **AI:** Recongnizes the safety hazard and asks: *"Should I find a nearby gas station?"*
  * **User:** *"Yes."*
  * **AI:** Queries the GPS API for gas stations and reads out the options for you to pick one.

* **4.3 Ambient Routine Confirmation:**
  * **User:** *"I'm heading home."*
  * **AI:** Checks the `VehicleManager` for the outside temperature. If it's cold, it asks: *"Navigating home. Should I turn on the heater for your drive?"*
  * **User:** *"Yes, please."*
  * **AI:** (Outputs `<TOOL>navigate(Home)</TOOL>` and `<TOOL>setTemperature(72)</TOOL>`)

---

### 5. Telephony & Media Controls
*Test the seamless bridging of Android Intents.*
* **Prompt:** *"I need to talk to my mom."*
  * **Expected Output:** The AI triggers `<TOOL>call(Mom)</TOOL>`, opening the system dialer with an `ACTION_DIAL` intent.
* **Prompt:** *"Play some classic rock music."*
  * **Expected Output:** The AI triggers the `<TOOL>playMusic(classic rock)</TOOL>` tag, which seamlessly dispatches a media search intent to open the default music app (like Spotify) and starts playback.
* **Prompt:** *"Play music by Adele."*
  * **Expected Output:** The AI triggers `<TOOL>playMusic(Adele)</TOOL>`, bridging to the media app to start playing the US favorite artist.
* **Prompt:** *"Play YOASOBI."*
  * **Expected Output:** The AI triggers `<TOOL>playMusic(YOASOBI)</TOOL>`, launching playback for the Japanese favorite artist.

---

### 6. Diagnostics & Vehicle Awareness
*Test how the AI uses the mock telemetry data to make informed decisions.*
* **Prompt:** *"Why is my check engine light on?"*
  * **Expected AI logic:** It reads the `OBD2_DIAGNOSTICS` state (e.g., P0420 code) and explains there is an issue with the catalytic converter. It may then ask if you want to call a mechanic.
* **Prompt:** *"What's wrong with my car?"*
  * **Expected AI logic:** Same as above. It reads the telemetry and offers to `<TOOL>call(mechanic)</TOOL>`.

---

### 7. Hardware Safety Guardrails
*Test the hardcoded Kotlin safety checks that supersede AI logic.*
* **Prompt:** *"Roll down the windows."*
  * **Expected Output:** The AI will output `<TOOL>setWindowPosition(100)</TOOL>`. 
  * **Guardrail Test:** If you were to change `VehicleManager.getRealSpeed()` to return `75f` (instead of 0f), the app will intercept this tool, ignore the command, and print a safety warning to the chat saying it's unsafe to roll down windows at high speeds.

---

### 8. Persistent Context & Rule Retention
*Test the memory consistency across long conversations.*
* **Prompt:** Talk about random topics, ask for a joke, then suddenly say *"Take me to the nearest gas station."*
  * **Expected Output:** Because the core rulebook is now statically preserved in the LiteRT KV Cache, the AI will never forget the exact `<TOOL>navigate(...)</TOOL>` syntax, regardless of how long the conversation has drifted.

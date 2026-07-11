# VehicleEdge Assistant - Final Demo Script

This script provides a step-by-step guide for recording your OEM demonstration video. It highlights the completely offline, agentic capabilities of the assistant, perfectly synced TTS, and direct hardware integration.

---

### **Scene 1: Granular Climate Control (Zero-Hallucination VHAL)**
*Showcase the system's ability to precisely manipulate the car's HVAC system using natural language.*

**1. Command:** `"Increase temperature"`
* **Visual:** The assistant processes the query instantly. 
* **Audio/Action:** "I'm warming it up." (The cabin temperature increases).

**2. Command:** `"Decrease temperature"`
* **Audio/Action:** "I'm cooling it down." (The cabin temperature decreases).

**3. Command:** `"Set temperature to 72 degrees"`
* **Audio/Action:** "I've set the temperature to 72 degrees." (The VHAL sets exact int/float value).

**4. Command:** `"Turn on climate control"` OR `"Turn on AC"`
* **Audio/Action:** The assistant acknowledges and powers on the main HVAC unit.

**5. Command:** `"Increase FAN speed"` OR `"Decrease FAN speed"`
* **Audio/Action:** The assistant actuates the blower motor intensity.

**6. Command:** `"Set Airflow direction to face and feet"`
* **Audio/Action:** "Adjusting airflow." (The assistant actuates the HVAC damper routing).

---

### **Scene 2: Contextual Awareness & Safety**
*Demonstrate the AI understanding implicit needs and recognizing dangerous situations.*

**7. Command:** `"I am feeling cold"`
* **AI Logic:** Instead of just changing the temperature, the AI recognizes the human sentiment and proactively offers: *"Would you like me to turn on the seat heater?"*
* **Follow-up:** `"Yes"` -> Turns on seat heater.

**8. Command:** `"I am feeling hot"`
* **Audio/Action:** "I'm cooling it down." (Directly lowers temperature).

**9. Command:** `"My window is freezing"` OR `"My window is fogging"`
* **AI Logic:** The AI recognizes this as a visibility hazard and immediately triggers the defroster or max-AC to clear the windshield to keep the driver safe.

---

### **Scene 3: Agentic Routing & Context Memory**
*Demonstrate the newly implemented GPS integration (Tokyo), background agentic loops, and multi-turn memory.*

**10. Command:** `"Navigate me to Tokyo Tower"`
* **Action:** The AI immediately triggers the `<TOOL>navigate(Tokyo Tower)</TOOL>` tool.
* **Visual:** An Android Intent is dispatched, seamlessly launching Google Maps (or the default navigation app) and instantly calculating a route to Tokyo Tower.

**11. Command:** `"Suggest nearby places to visit around Tokyo"`
* **AI Logic:** *This triggers the recursive Agentic Loop.*
    1. The AI silently queries the live Nominatim GPS API in the background.
    2. It reads the JSON result.
    3. It generates a human-readable list.
* **Audio/Action:** *"I found these options nearby: 1. Sensō-ji Temple, 2. Tokyo Skytree, 3. Meiji Shrine. Which one would you like to visit?"* (Note the flawless, perfectly paced 150-WPM TTS reading the numbered list without unnatural stuttering).

**12. Follow-up Command:** `"The second one"` OR `"Take me to the Skytree"`
* **AI Logic:** The AI remembers the context of the previous list, understands your choice, and automatically executes `<TOOL>navigate(Tokyo Skytree)</TOOL>` to route you there without you having to say the full address.

**13. Command:** `"I am running out of fuel"` OR `"I have low battery"`
* **AI Logic:** The AI recognizes the urgency and safety hazard. It proactively asks: *"Should I find a nearby gas station?"*
* **Follow-up Command:** `"Yes"`
* **Action:** The AI queries the Nominatim GPS API for nearby gas stations, reads the list to you, and allows you to select one to navigate to.

---

### **Scene 4: Seamless Android Intent Bridging (Media & Calls)**
*Demonstrate how the Assistant hooks deeply into Android's native OS to launch apps and play music based on regional context.*

**13. Command:** `"Play music by Adele"` OR `"Play YOASOBI"`
* **Action:** The AI triggers `<TOOL>playMusic(Adele)</TOOL>`.
* **Visual/Audio:** The Assistant utilizes the `MediaBrowserService` or a deep-link Intent to instantly open Spotify (or the default media player) and begin playback without any manual screen tapping.

**14. Command:** `"I need to talk to my mom"`
* **Action:** The AI triggers `<TOOL>callContact(Mom)</TOOL>`.
* **Visual:** The system Dialer app opens with an `ACTION_DIAL` intent, ready to place the call.

---

### **Video Recording Tips:**
1. **Pacing:** Pause for 2-3 seconds after the AI finishes speaking before you give your next command. This allows the video viewer to digest the interaction.
2. **Visuals:** Ensure the "typewriter" effect is clearly visible on camera. It is now perfectly synced to the TTS audio, making the AI feel incredibly responsive and natural.
3. **Logcat:** If possible, do a split-screen or B-roll showing Android Studio Logcat `[AssistantSession]` tags to prove to OEMs that the tool execution and API calls are happening dynamically and autonomously on the edge!

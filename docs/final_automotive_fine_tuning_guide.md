# Automotive AI: Comprehensive LLM Fine-Tuning Guide

This guide is for the Machine Learning / AI Engineering team. It explains how to fine-tune a large language model (LLM) to act as the autonomous reasoning engine for the Android Automotive OS (`VehicleEdgeAssistant`), covering both standard reactive queries and the new proactive notification paradigms.

---

# PART 1: Core Architecture & Base Datasets

## 1. The Pre-Generated Training Datasets
You do NOT need to manually build a training dataset for the core features. The Android engineering team has already provided two mathematically perfect, auto-generated datasets in the project root:

1. **`train.jsonl` (3,080 rows)**
   - This is your actual training file. It contains 2,580 positive Tool RAG examples and 500 negative (Chit-chat) examples. It is bilingual (English/Japanese) and perfectly handles context-aware empathy (Driver Moods).
   - *Format:* Standard OpenAI `messages` format (`role: system`, `role: user`, `role: model`).

2. **`ML_Exact_Training_Mapping.csv`**
   - This is your **Architecture Cheat Sheet**. It contains 4 columns: `Tool Handler`, `User Intent`, `Dynamic Input`, and `Exact LLM Output Expected`. 
   - Open this file in Excel or Numbers. The 4th column proves exactly how the LLM should reply (e.g., outputting the `<TOOL>` tag, or dynamically changing its tone if the driver is tired).

## 2. The Android Middleware Architecture (Crucial)
You do **not** need to train the model to parse the car's binary hardware signals or understand physics constraints (e.g., "Don't open the windows at 85mph"). 

The Android app acts as a middleware that:
1. Translates the car's sensors into an English string: `[System Context: Speed=85mph, AC=ON | DriverMood=Tired]`
2. Uses RAG to find the 4 most relevant tools and injects them into the `=== AVAILABLE TOOLS ===` block.
3. Catches the LLM's `<TOOL>setWindowPosition(100)</TOOL>` output and securely evaluates it against the vehicle's physics constraints *before* sending it to the CAN bus.

**Your only goal during fine-tuning is to train the model on Text-In (System Prompt + Context + User string) to Text-Out (Empathy + Tool Tags).**

## 3. Training Infrastructure
Because this model will run locally on mobile device hardware (NPU/GPU) inside the vehicle:
1. Train using Parameter-Efficient Fine-Tuning (PEFT) methods like **QLoRA**.
2. Base Model Recommendation: **Gemma-IT (2B or 7B)** or a **Qualcomm SA8295 Optimized Llama-3** model.
3. Export the final merged weights to a LiteRT `.bin` or `.litertlm` format for local on-device inference.

*If you need to regenerate the dataset with new tools, simply run `python3 generate_synthetic_dataset.py`.*

## 4. Contextual Empathy & Tone Shifting (CRUCIAL)
The most important feature of this model is its ability to act as a "Silent Copilot." The Android app uses an in-cabin camera to detect the driver's facial expressions and injects a `DriverMood` variable into the System Context. 

You must ensure the model learns to shift its output **tone** based on this variable, exactly as demonstrated in the training datasets:

- **If `DriverMood=Frustrated / Frowning`:** The model must be entirely silent and strictly mechanical. (e.g., Output: `<TOOL>setTemperature(72)</TOOL>` with no conversational fluff).
- **If `DriverMood=Tired / Yawning`:** The model must be proactive and suggest coffee or breaks. (e.g., Output: `"I've lowered the temperature. By the way, you look tired. Would you like me to route to a coffee shop? <TOOL>setTemperature(68)</TOOL>"`).
- **If `DriverMood=Happy / Smiling`:** The model should match the driver's energetic tone.

**Do not train the model to be a static, robotic assistant.** The entire purpose of the `train.jsonl` dataset is to teach the model to dynamically evaluate the `[System Context: DriverMood=...]` string before generating its text.

## 5. Rich Contextual Intelligence & Conversation Memory
The dataset explicitly trains the model to generate conversational flourishes based on the entire `System Context` block (e.g., Time of day, Speed, Occupants). 
Additionally, the dataset includes over 100 rows of **Multi-Turn Conversation Memory** (`Memory:` block in the system prompt) used for:
- **Pronoun Resolution:** (e.g. User: "Let's go to the second one" -> Resolves to the specific coffee shop mentioned in the memory).
- **Follow-up Adjustments:** (e.g. User: "Make it a bit warmer" -> Increases temperature after a previous AC command). 

---

# PART 2: The Proactive Use-Case Expansion

This section extends the base guide by introducing a new interaction paradigm: **Proactive Notifications** (e.g., the assistant initiating a conversation about an upcoming birthday or anniversary without a prior user prompt).

## 1. The Proactive Training Dataset (Delta Update)
You do NOT need to run a massive new training phase. You must generate an additional **30-50 synthetic dialogue rows** specifically representing the proactive calendar use-case, and append them directly to the existing `train.jsonl` for your next LoRA fine-tuning run.

These rows introduce a brand new Context Trigger paradigm: `[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, TargetRelation=Wife, DaysRemaining=15]`

## 2. The Android Middleware Architecture (Proactive Wakeup)
Unlike standard interactions where the user speaks first, the Android app acts as a background scheduler for this feature.
1. **The Wakeup:** The Android app detects an upcoming calendar event.
2. **Silent Suppression (Crucial):** Before waking the model, the app evaluates the cabin camera. If `Occupants > 1`, the app *aborts immediately* to prevent ruining a surprise. The LLM is never invoked.
3. **The Injection:** If the driver is alone, the app wakes the LLM by sending a silent, simulated user payload: `[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, TargetRelation=Wife, DaysRemaining=15, DriverMood=Neutral]`
4. **The Response:** The LLM must immediately output conversational text with `<MOOD>` and `<CUES>` tags to initiate speech with the driver.

## 3. Tool Chaining & Sequential Execution
The proactive dataset introduces multi-step planning workflows (e.g., finding a restaurant, booking the table, and then adding it to the calendar).
Because the Android middleware securely evaluates exactly **one tool call per turn** against the vehicle, the model is explicitly trained to chain these actions conversationally over multiple turns. Do **not** train the model to output multiple `<TOOL>` tags in a single string. *(See Part 3 transcripts for examples).*

## 4. Out-of-Domain (OOD) Guardrails
Because the assistant opens the conversation by offering to "help plan," users will inevitably test its limits (e.g., *"Yeah, order her that gold necklace she looked at yesterday"*).
The 30-50 row delta dataset explicitly contains **Out-of-Domain rejection examples**. The model must gracefully decline unsupported actions without hallucinating fake tool calls (like `<TOOL>order_retail_item()</TOOL>`).

## 5. Acoustic Grounding & Tone (TTS Constraints)
Because proactive notifications are read aloud by the vehicle's Text-To-Speech (TTS) engine:
- **Spoken Brevity:** Ensure the model never generates proactive responses longer than 2-3 sentences.
- **No Markdown:** Output must **never** contain bullet points (`*`), bold tags (`**`), or lists. TTS engines translate these poorly (often literally speaking the word "asterisk").
- **Default Moods:** For proactive event reminders, default to `<MOOD>Helpful</MOOD><CUES>Calendar</CUES>` for the opening line.

---

# PART 3: Proactive Calendar Specifications (Dataset Construction)

Use the following exact specifications to construct the 30-50 dialogue rows for the proactive use-case.

## 1. System Prompt Augmentation & Persona
Add the following directives to the base system prompt to govern proactive behavior:

> [!IMPORTANT]
> **Proactive Logic Directives:**
> When receiving a `TriggerType=Proactive_Notification` for an `Event_Reminder`, you must initiate the conversation. 
> 1. Acknowledge the event warmly and naturally.
> 2. State the time remaining (`DaysRemaining`).
> 3. If `NeedsAssistancePrompt=True`, **always** end your first message by offering to help plan or organize the event. 
> 4. Do not execute any tool calls until the user explicitly agrees to your offer for help.
> 5. Default to `<MOOD>Helpful</MOOD><CUES>Calendar</CUES>`.

## 2. Few-Shot Dialogue Transcripts (Training Blueprints)

### Scenario A: The Happy Path (User accepts help)
**System Input:**
`[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, EventType=Birthday, TargetRelation=Wife, EventDate=August 30, DaysRemaining=15, NeedsAssistancePrompt=True, Occupants=1]`

**Assistant (Opening Line):** 
`<MOOD>Helpful</MOOD><CUES>Calendar</CUES>Hey there, just a heads up from your calendar—your wife's birthday is coming up in 15 days, on August 30th. That's plenty of time to plan something wonderful. Would you like me to help organize a celebration this year?`

**User:** 
`Okay, thanks for the reminder. Yes, I'd love some help, I completely lose track of time with work.`

**Assistant:** 
`<MOOD>Happy</MOOD><CUES>Listening</CUES>That's exactly what I'm here for, let's make it special. Were you thinking dinner out, a small gathering with friends, or something intimate with just the two of you?`

**User:** 
`Something intimate just the two of us, a nice dinner would be perfect.`

**Assistant:** 
`<MOOD>Thinking</MOOD><CUES>Search</CUES><TOOL>search_restaurants(party_size=2, atmosphere="romantic", date="2026-08-30")</TOOL>`

**System Input:**
`[Tool Result: Found 3 romantic restaurants...]`

**Assistant:** 
`<MOOD>Helpful</MOOD><CUES>List</CUES>A nice dinner sounds perfect. I found a few highly-rated romantic restaurants nearby, including Trattoria Romana. Did you have a specific cuisine in mind, or should we go with Italian?`

### Scenario B: Multi-Step Logic (One Tool Per Turn chaining)
**Assistant:** 
`<MOOD>Helpful</MOOD><CUES>List</CUES>A nice dinner sounds perfect. I can look up some highly-rated romantic restaurants nearby...`

**User:** 
`Book the Italian place for 7 PM and put it on my calendar.`

**Assistant (Action 1 - Book Restaurant):** 
`<MOOD>Thinking</MOOD><CUES>Action</CUES>Let me lock in that reservation first. <TOOL>book_restaurant(restaurant_name="Trattoria Romana", time="19:00", party_size=2, date="2026-08-30")</TOOL>`

**System Input:**
`[Tool Result: Success. Reservation confirmed.]`

**Assistant (Action 2 - Update Calendar):** 
`<MOOD>Thinking</MOOD><CUES>Calendar</CUES>Perfect, the table is ours. Now I'll add it to your calendar. <TOOL>book_calendar_event(title="Dinner at Trattoria Romana", date="2026-08-30", time="19:00")</TOOL>`

**System Input:**
`[Tool Result: Success. Calendar updated.]`

**Assistant:** 
`<MOOD>Happy</MOOD><CUES>Success</CUES>All set! I've booked Trattoria Romana for 7 PM on August 30th and added it to your schedule. Is there anything else you'd like to plan?`

### Scenario C: Out-of-Domain (OOD) Guardrail
**System Input:**
`[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder...]`

**Assistant (Opening Line):** 
`<MOOD>Helpful</MOOD><CUES>Calendar</CUES>Hey there, just a heads up from your calendar—your wife's birthday is coming up in 15 days...` 

**User:** 
`Yeah, thanks! Actually, can you just order her that gold necklace she was looking at online yesterday?`

**Assistant:** 
`<MOOD>Apologetic</MOOD><CUES>Error</CUES>I can't place retail orders just yet, but I can certainly help you block off some time on your calendar to go shopping, or book a nice dinner. What works best?`

## 3. Required Tool Schemas (Custom Tag Syntax)
To support the planning phase, the LLM must be trained on the following tool interfaces:

### `search_restaurants`
* **Signature:** `search_restaurants(party_size: int, atmosphere: string, cuisine: string, date: string)`
* **Example:** `<TOOL>search_restaurants(party_size=2, atmosphere="romantic", cuisine="Italian", date="2026-08-30")</TOOL>`

### `book_calendar_event`
* **Signature:** `book_calendar_event(title: string, date: string, time: string)`
* **Example:** `<TOOL>book_calendar_event(title="Dinner at Trattoria Romana", date="2026-08-30", time="19:00")</TOOL>`

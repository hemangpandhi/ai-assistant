# Automotive AI: Proactive Use-Cases Addendum

This guide is an addendum for the Machine Learning / AI Engineering team. It extends the base LLM Fine-Tuning Guide by introducing a new interaction paradigm: **Proactive Notifications** (e.g., the assistant initiating a conversation about an upcoming birthday or anniversary without a prior user prompt).

## 1. The Proactive Training Dataset (Delta Update)
You do NOT need to run a massive new training phase. You will be provided with an additional **30-50 synthetic dialogue rows** specifically representing the proactive calendar use-case. 

- Append these new rows directly to the existing `train.jsonl` (3,080 rows) for your next PEFT/LoRA fine-tuning run.
- These rows introduce a brand new Context Trigger paradigm: `[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, TargetRelation=Wife, DaysRemaining=15]`
- Like the base dataset, they perfectly utilize the required `<MOOD>`, `<CUES>`, and `<TOOL>` XML tags.

## 2. The Android Middleware Architecture (Proactive Wakeup)
Unlike standard interactions where the user speaks first, the Android app acts as a background scheduler for this feature.

1. **The Wakeup:** The Android app detects an upcoming calendar event (e.g., Wife's Birthday in 15 days).
2. **Silent Suppression (Crucial):** Before waking the model, the app evaluates the cabin camera. If `Occupants > 1`, the app *aborts immediately* to prevent ruining a surprise. The LLM is never invoked.
3. **The Injection:** If the driver is alone, the app wakes the LLM by sending a silent, simulated user payload: `[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, TargetRelation=Wife, DaysRemaining=15, DriverMood=Neutral]`
4. **The Response:** The LLM must immediately output conversational text with `<MOOD>` and `<CUES>` tags to initiate speech with the driver.

**Your goal is to train the model to react to `TriggerType=Proactive_Notification` by speaking first, rather than waiting for a user command.**

## 3. Tool Chaining & Sequential Execution
The proactive dataset introduces multi-step planning workflows (e.g., finding a restaurant, booking the table, and then adding it to the calendar).

Because the Android middleware securely evaluates exactly **one tool call per turn** against the vehicle, the model is explicitly trained to chain these actions conversationally over multiple turns. Do **not** train the model to output multiple `<TOOL>` tags in a single string.

*Example Sequential Flow in the Training Data:*
- **Turn 1 LLM Output:** `<MOOD>Thinking</MOOD><CUES>Action</CUES>Let me lock in that reservation first. <TOOL>book_restaurant(restaurant_name="Trattoria Romana", time="19:00", party_size=2, date="2026-08-30")</TOOL>`
- *(Middleware executes, succeeds, and injects result)*
- **Turn 2 LLM Output:** `<MOOD>Thinking</MOOD><CUES>Calendar</CUES>Perfect, the table is ours. Now I'll add it to your calendar. <TOOL>book_calendar_event(title="Dinner at Trattoria Romana", date="2026-08-30", time="19:00")</TOOL>`

## 4. Out-of-Domain (OOD) Guardrails
Because the assistant opens the conversation by offering to "help plan," users will inevitably test its limits (e.g., User: *"Yeah, order her that gold necklace she looked at yesterday"*).

The 30-50 row delta dataset explicitly contains **Out-of-Domain rejection examples**. 
- You must ensure the model learns to gracefully decline unsupported actions without hallucinating fake tool calls (like `<TOOL>order_retail_item()</TOOL>`).
- The model should steer the user back to supported actions: *"I can't place retail orders just yet, but I can certainly help you block off time to go shopping, or book a nice dinner."*

## 5. Acoustic Grounding & Tone (TTS Constraints)
Just as Contextual Empathy dictates the model's reaction to `DriverMood`, proactive notifications require strict formatting because they are read aloud by the vehicle's Text-To-Speech (TTS) engine.

- **Spoken Brevity:** Ensure the model never generates proactive responses longer than 2-3 sentences. Drivers cannot safely listen to long essays while navigating.
- **No Markdown:** Output must **never** contain bullet points (`*`), bold tags (`**`), or lists. TTS engines translate these poorly (often literally speaking the word "asterisk").
- **Default Moods:** For proactive event reminders, train the model to default to `<MOOD>Helpful</MOOD><CUES>Calendar</CUES>` for its opening line.

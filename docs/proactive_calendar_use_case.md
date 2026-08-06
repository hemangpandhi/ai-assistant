# Proactive Calendar Notification: AI Training Specification

This document outlines the technical and conversational specifications for the **Proactive Calendar Notification** use-case (e.g., Wife's Birthday Reminder) for the VehicleEdgeAssistant. This data is tailored to the project's current LoRA fine-tuning architecture and custom tag syntax.

---

## 1. Trigger Context Definition (System Context)

When the proactive calendar service wakes the assistant, it must inject a specific block of context that explains *why* the assistant is initiating the conversation. 

**Format Pattern (Comma-Separated `key=value`):**
```text
[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, EventType=Birthday, TargetRelation=Wife, TargetName=Sarah, EventDate=August 30, DaysRemaining=15, NeedsAssistancePrompt=True, DriverMood=Neutral, Occupants=1, Time=Morning]
```

### Context Field Definitions

| Field | Purpose | Example Value |
| :--- | :--- | :--- |
| `TriggerType` | Informs the LLM it is initiating the conversation, not responding to a query. | `Proactive_Notification` |
| `Intent` | The specific category of the proactive action. | `Event_Reminder` |
| `EventType` | The nature of the calendar event. | `Birthday`, `Anniversary` |
| `TargetRelation` | Relationship of the subject to the user (used for personalized phrasing). | `Wife`, `Son` |
| `DaysRemaining` | Integer value used to ground the LLM's urgency. | `15` |
| `NeedsAssistancePrompt` | Boolean flag instructing the LLM to end its opening statement by offering to help plan. | `True` |
| `Occupants` | Camera-derived integer. | `1` |

> [!CAUTION]
> **App-Side Suppression (Occupants > 1):**
> If the vehicle camera detects `Occupants > 1`, the proactive notification must be **silently suppressed at the App/Service layer**. Do not invoke the LLM or send the context payload at all to avoid accidentally ruining a surprise or broadcasting private information.

---

## 2. System Prompt Augmentation & Persona Directives

Add the following directives to the base system prompt to govern proactive behavior and tone:

> [!IMPORTANT]
> **Proactive Logic Directives:**
> When receiving a `TriggerType=Proactive_Notification` for an `Event_Reminder`, you must initiate the conversation. 
> 1. Acknowledge the event warmly and naturally.
> 2. State the time remaining (`DaysRemaining`).
> 3. If `NeedsAssistancePrompt=True`, **always** end your first message by offering to help plan or organize the event. 
> 4. Do not execute any tool calls until the user explicitly agrees to your offer for help.
> 5. You must always include `<MOOD>` and `<CUES>` tags in your response. For proactive event reminders, default to `<MOOD>Helpful</MOOD>` and `<CUES>Calendar</CUES>`.
>
> **Persona & Tone Directives (In-Car Experience):**
> * **Empathy vs. Utility:** Strike a balance. Sound warm and helpful for a birthday, but avoid being overly enthusiastic, robotic, or overly familiar. 
> * **Acoustic Grounding:** Write dialogue that sounds natural when *spoken aloud*. Avoid complex clauses or unnatural transitions.

---

## 3. Few-Shot Dialogue Transcripts (LoRA Training Data)

The following transcripts map the system context to the desired model outputs using the project's native `<TOOL>`, `<MOOD>`, and `<CUES>` XML tags.

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

---

### Scenario B: User Declines Help

**System Input:**
`[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, EventType=Birthday, TargetRelation=Wife, EventDate=August 30, DaysRemaining=15, NeedsAssistancePrompt=True, Occupants=1]`

**Assistant (Opening Line):** 
`<MOOD>Helpful</MOOD><CUES>Calendar</CUES>Hey there, just a heads up from your calendar—your wife's birthday is coming up in 15 days. That's plenty of time to plan something wonderful. Would you like me to help organize a celebration this year?`

**User:** 
`No thanks, I already have it sorted.`

**Assistant:** 
`<MOOD>Happy</MOOD><CUES>Default</CUES>Sounds great! Let me know if you need any last-minute help with flowers or reservations down the road. Have a good drive.`

---

### Scenario C: Out-of-Domain (OOD) Guardrail

**System Input:**
`[System Context: TriggerType=Proactive_Notification, Intent=Event_Reminder, EventType=Birthday, TargetRelation=Wife, EventDate=August 30, DaysRemaining=15, NeedsAssistancePrompt=True, Occupants=1]`

**Assistant (Opening Line):** 
`<MOOD>Helpful</MOOD><CUES>Calendar</CUES>Hey there, just a heads up from your calendar—your wife's birthday is coming up in 15 days...` *(Continues as normal)*

**User:** 
`Yeah, thanks! Actually, can you just order her that gold necklace she was looking at online yesterday?`

**Assistant:** 
`<MOOD>Apologetic</MOOD><CUES>Error</CUES>I can't place retail orders just yet, but I can certainly help you block off some time on your calendar to go shopping, or book a nice dinner. What works best?` 
*(Note: Graceful degradation without hallucinating a fake tool call).*

---

### Scenario D: Multi-Step Logic (One Tool Per Turn)

Since the architecture limits tool execution to one per turn, the model must explicitly break down complex user requests.

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

---

## 4. Required Tool Schemas (Custom Tag Syntax)

To support the planning phase of this use-case, the LLM must be trained on the following tool interfaces in the `<TOOL>` syntax.

### `search_restaurants`
Used to find venues based on user preferences.
* **Signature:** `search_restaurants(party_size: int, atmosphere: string, cuisine: string, date: string)`
* **Example:** `<TOOL>search_restaurants(party_size=2, atmosphere="romantic", cuisine="Italian", date="2026-08-30")</TOOL>`

### `book_calendar_event`
Used to block out time or save a reminder in the user's calendar once a plan is finalized.
* **Signature:** `book_calendar_event(title: string, date: string, time: string)`
* **Example:** `<TOOL>book_calendar_event(title="Dinner at Trattoria Romana", date="2026-08-30", time="19:00")</TOOL>`

---

## 5. Scope: LoRA Fine-Tuning Integration

Because the system is utilizing a single LoRA fine-tune (currently ~862 examples across 25 use cases), you do not need thousands of rows. Instead, scope this feature to match the existing dataset density:

* **Training Target:** Generate **30 to 50 representative dialogue examples** for this specific use case to append to the 862-example training set.
* **Scenario Distribution:** Ensure the 30-50 examples cover:
  * The Happy Path (20 examples)
  * The Rejection Path (5 examples)
  * Out of Domain / Missing Capabilities (10 examples)
  * One-tool-per-turn chaining (5-10 examples)

---

## 6. In-Car & TTS (Text-to-Speech) Constraints

Because this is a `VehicleEdgeAssistant`, the outputs will be read aloud to a driver.

* **Spoken Brevity:** Ensure the model never generates responses longer than 2-3 sentences at a time. Drivers cannot listen to long essays while navigating.
* **No Markdown:** Train the model to **never** output bullet points (`*`), bold tags (`**`), or lists, as these translate poorly in most TTS engines (the TTS often literally says "asterisk").
* **Implicit Confirmations:** Ensure the model doesn't over-confirm (e.g., repeating exactly what the user said before answering). Conversations must feel fluid.

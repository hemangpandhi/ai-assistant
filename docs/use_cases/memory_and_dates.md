# Use-Case: Generalized Memory & Special Occasions

The Assistant is configured with an active `MemoryManager` and specific prompt guardrails to recall important dates and proactively offer automotive-centric services (like booking a restaurant) when those dates are referenced.

## Example Flow: Saving a Date
1. **User**: "Please save my wife's birthday: 8th May 1996."
2. **LLM**: Parses the command and executes the tool: `I will remember that for you. <TOOL>remember(Wife's birthday is 8th May 1996)</TOOL>`
3. **ToolManager**: Intercepts the `<TOOL>remember(FACT)</TOOL>` command and saves the string into persistent `SharedPreferences` under the `user_memory` key.

## Generalized Memory Architecture
The memory system is generic. The `<TOOL>remember(FACT)</TOOL>` tool simply stores any string the user provides into persistent `SharedPreferences` under the `user_memory` key.

### Example Flow 1: Generic Fact
1. **User**: "Remember that I parked in spot 4B."
2. **LLM**: `I will remember that for you. <TOOL>remember(Parked in spot 4B)</TOOL>`
3. **ToolManager**: Saves the fact.
4. **User** (later): "Where did I park?"
5. **Context Engine**: Retrieves `user_memory` and injects it into the prompt.
6. **LLM**: "You parked in spot 4B." (Standard response, no special actions taken).

### Example Flow 2: Recalling and Proactive Booking (Special Occasion)
If the memory relates to a special occasion, the LLM uses its semantic understanding to trigger a proactive booking workflow.

1. **User**: "What's the date today?" or "Do I have anything special coming up on May 8th?"
2. **Context Engine (`LLMManager.kt`)**: 
   - Detects the keywords (`birthday`, `wife`, `date`, `remember`).
   - Retrieves `user_memory` from `SharedPreferences`.
   - Injects the generalized rule: *"MEMORY: If the user asks about a saved memory or date, provide the information. If the memory relates to a special occasion (like a birthday or anniversary), proactively ask if they would like to plan a dinner or do something special to celebrate."*
3. **LLM**: Formulates the response based on the rule.
   - *"It's your wife's birthday on May 8th! Would you like to plan a dinner or do something to make her feel special?"*
4. **User**: "Yes, book a restaurant for us at 8 PM."
5. **LLM**: Generates the tool call.
   - `<TOOL>bookRestaurant(8 PM, 2 people)</TOOL>`
6. **ToolManager / Agentic Loop**: Successfully completes the reservation process.

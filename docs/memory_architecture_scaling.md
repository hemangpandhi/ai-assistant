# Scaling Context Routing & Memory Architecture

The current implementation in `LLMManager.kt` relies on **Heuristic Rule-Based Routing** (hardcoded `String.contains()` checks). While this is excellent for rapid prototyping, it is brittle, error-prone, and impossible to scale for a production Software Defined Vehicle (SDV) because human language has infinite variations.

To scale this for production, the architecture must transition to **Semantic Understanding**. Below are the three recommended architectural pillars for scaling this offline on-device system.

## 1. On-Device Semantic Intent Routing (NLU Classifier)

Instead of relying on the heavy, generative LLM (Gemini Nano) to figure everything out or using hardcoded strings, place a very small, lightning-fast intent classifier upstream.

- **How it works**: Use a quantized on-device BERT model (via TensorFlow Lite or ONNX).
- **Process**: 
  1. User says: "I'm freezing in here."
  2. NLU Classifier outputs: `INTENT_HVAC_CONTROL` (Confidence: 0.98).
  3. The `LLMManager` receives the intent and *only* injects the HVAC vehicle state and HVAC rules into the Gemini Nano prompt.
- **Why it scales**: You train the classifier on thousands of varied phrases. It understands that "turn up the heat", "make it warmer", and "I'm freezing" all map to the exact same intent, completely eliminating the need for `if (q.contains("cold"))`.

## 2. On-Device RAG (Retrieval-Augmented Generation)

Currently, the `MemoryManager` dumps the entire `user_memory` string into the prompt. If the user saves 50 facts over a year, this will overflow Gemini Nano's context window and increase latency.

- **How it works**: Use an on-device Vector Database (like ObjectBox or SQLite with VSS) combined with a local embedding model.
- **Process**:
  1. User says: "Remember my license plate is ABC-1234."
  2. System converts that sentence into a mathematical vector (embedding) and saves it to the local Vector DB.
  3. Months later, User asks: "What's my license plate for the toll booth?"
  4. The system embeds the question, performs a nearest-neighbor search in the database, and retrieves the highly-relevant ABC-1234 fact.
  5. *Only* that specific fact is injected into the LLM context.
- **Why it scales**: It supports virtually unlimited memory storage with constant O(1) retrieval time and guarantees the LLM only receives highly relevant context, saving tokens and NPU cycles.

## 3. Agentic Memory Search (Tool-Driven Retrieval)

If we want to give the AI maximum autonomy without relying on pre-routing, we can flip the architecture so the LLM manages its own memory.

- **How it works**: We provide the LLM with a new tool in the `vehicle_skills_registry.json` called `<TOOL>queryMemory(SEARCH_TERM)</TOOL>`.
- **Process**: 
  1. User asks: "Where did I park?"
  2. The LLM's prompt doesn't contain the memory. Instead, the LLM realizes it doesn't know the answer and executes `<TOOL>queryMemory(parking location)</TOOL>`.
  3. The `ToolManager` searches the local database and returns the result in the agentic loop.
  4. The LLM then answers the user.
- **Why it scales**: The system doesn't need to guess if the user is asking a memory question. The LLM agent figures it out autonomously.

## Immediate Pragmatic Fix (Pre-Production)

If we cannot implement an NLU router or Vector DB immediately, the best stopgap solution for the current architecture is **Universal Context Injection**. 

Since the `user_memory` is currently very small, we should stop trying to guess when the user wants memory. Instead, we should simply *always* inject the `Memory: $userMemory` string into the `basePrompt` for *every* query. Gemini Nano's attention mechanisms are smart enough to simply ignore the memory string if the user is asking about something unrelated (like "Turn on the AC").

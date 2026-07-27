# Automotive AI: LLM Fine-Tuning Guide

This guide is for the Machine Learning / AI Engineering team. It explains how to fine-tune a large language model (LLM) to act as the autonomous reasoning engine for the Android Automotive OS.

## 1. The Pre-Generated Training Datasets
You do NOT need to manually build a training dataset. The Android engineering team has already provided two mathematically perfect, auto-generated datasets in the project root:

1. **`train.jsonl` (3,080 rows)**
   - This is your actual training file. It contains 2,580 positive Tool RAG examples and 500 negative (Chit-chat) examples. It is bilingual (English/Japanese) and perfectly handles context-aware empathy (Driver Moods).
   - *Format:* Standard OpenAI `messages` format (`role: system`, `role: user`, `role: model`).

2. **`ML_Exact_Training_Mapping.csv`**
   - This is your **Architecture Cheat Sheet**. It contains 4 columns: `Tool Handler`, `User Intent`, `Dynamic Input`, and `Exact LLM Output Expected`. 
   - Open this file in Excel or Numbers. The 4th column ("Exact LLM Output Expected") proves exactly how the LLM should reply (e.g., outputting the `<TOOL>` tag, or dynamically changing its tone if the driver is tired).

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

## 5. Rich Contextual Intelligence
In addition to Mood, the dataset explicitly trains the model to generate conversational flourishes based on the entire `System Context` block:
- **Time:** Generates "Good morning!" or "Good evening." based on the `Time` variable.
- **Speed:** Mentions highway speeds if `Speed >= 65` or parked status if `Speed = 0`.
- **Occupants:** Adapts climate/media responses for rear passengers if `Occupants > 1`.
- **Acoustics:** Lowers the AC `Fan` speed automatically if the user wants to make a phone call and the fan is loud.

## 6. Multi-Turn Conversation Memory
The dataset includes over 100 rows of **Multi-Turn Conversation Memory**. In the System Prompt, you will see a `Memory:` block. 
The LLM is explicitly trained to use this block for:
- **Pronoun Resolution:** (e.g. User: "Let's go to the second one" -> Resolves to the specific coffee shop mentioned in the memory).
- **Follow-up Adjustments:** (e.g. User: "Make it a bit warmer" -> Increases temperature after a previous AC command). 
Ensure your training pipeline does not discard the `Memory:` block, as it is critical for continuous interaction.

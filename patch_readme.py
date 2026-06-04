import re

with open('README.md', 'r') as f:
    content = f.read()

features_content = """## ✨ Key Features
* **Hybrid LLM Architecture**: Seamlessly switch between the **Local Offline Model** (`LiteRT` Gemma) and the **Cloud Model** (`Gemini Flash`). 
* **Ultra-Low Latency**: Cloud integration uses custom Server-Sent Events (SSE) Streaming to bypass TCP wait states (bringing TTFT below 1.5s). Local integration uses precision KV Cache management to prevent prompt-bloat during multi-turn chats, ensuring sub-2.5s inference completely offline.
* **Semantic Tool Engine**: Employs a Retrieval-Augmented Generation (RAG) framework using Universal Sentence Encoders to dynamically load specific XML tools based on query intent.
* **Deep Hardware Actuation**: Parses logical AI commands (e.g. `<TOOL>setTemperature(70)</TOOL>`) into physical Android Automotive OS (AAOS) VHAL (Vehicle Hardware Abstraction Layer) calls.
* **Hardware Write Pre-Checks**: Analyzes the physical state of the vehicle prior to VHAL callback issuance, dramatically reducing redundant callbacks and avoiding 6-second Voice Interaction timeouts.
* **System Overlay Persistence**: Integrates Android's `VoiceInteractionSession` with internal lifecycle watchdog overrides (`setKeepAwake`) to maintain UI focus during dense AI background tasks.
* **Fully Offline Speech Stack**: Utilizes the Vosk API for wake-word detection, maintaining privacy while operating entirely decoupled from network connections.
"""

content = re.sub(
    r'## ✨ Key Features.*?(?=## 🚀)',
    features_content + '\n',
    content,
    flags=re.DOTALL
)

with open('README.md', 'w') as f:
    f.write(content)


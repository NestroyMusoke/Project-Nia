# Architecture

```mermaid
flowchart TD
    Camera["Android front camera"] --> Holistic["MediaPipe Holistic Landmarker"]
    Holistic --> V3["Frozen V3 transform — 64 × 272"]
    V3 --> LiteRT["3.88 MB Nia FP16 LiteRT model"]
    LiteRT --> Prob["32 isolated-sign probabilities"]
    LiteRT --> Emb["384-D personal embedding"]
    Emb --> Local["Local 3-shot prototypes"]
    Prob --> Blend["Frozen 0.35 / 0.65 blend"]
    Local --> Blend
    Blend --> Gate["Confidence + top-two margin gate"]
    Gate -->|accepted| Offline["Offline sign token"]
    Gate -->|uncertain| Clarify["Ask user to repeat/correct"]
    Offline -->|optional later| Gemma["Gemma offline language layer"]
    Offline -->|online| ADK["Gemini + Google ADK on Cloud Run"]
    ADK --> Firestore["Firestore goal/conversation memory"]
    ADK --> PubSub["Pub/Sub background jobs"]
```

Raw camera frames, landmarks, and personal embeddings remain on device. Only accepted labels and confidence metadata are sent to the online agent. Pub/Sub is reserved for non-urgent work; immediate replies stay synchronous.


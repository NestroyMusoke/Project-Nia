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
    Offline --> Buffer["Reviewable phrase buffer — max 20 tokens"]
    Gate -->|uncertain| Clarify["Ask user to repeat/correct"]
    Holistic --> Retarget["64-frame avatar motion retargeting"]
    Retarget --> Draft["Local draft motion"]
    Draft -->|fluent signer approval| Validated["Validated avatar vocabulary"]
    Buffer -->|optional later| Gemma["Gemma offline language layer"]
    Buffer -->|explicit Translate action| ADK["Gemini + Google ADK on Cloud Run"]
    Hearing["Hearing-person message"] --> ADK
    Validated --> ADK
    AndroidHealth["Versioned /healthz check"] --> ADK
    ADK -->|validated glosses only| Avatar["Native 3D signing avatar"]
    ADK --> Firestore["Firestore goal/conversation memory"]
    ADK --> PubSub["Pub/Sub background jobs"]
```

Raw camera frames, landmarks, avatar motion, and personal embeddings remain on device. Separately captured accepted signs accumulate in a bounded local buffer and remain reviewable until the user clears them. Only when the user explicitly requests translation are the accepted labels, confidence metadata, user-correction markers, and available validated gloss names sent to the online agent. A hearing-person message is sent only when supplied. Pub/Sub is reserved for non-urgent work; immediate replies stay synchronous.

Each installation creates a random anonymous `install-<UUID>` value in private
preferences. It separates Firestore conversation paths without using hardware,
account, advertising, or contact identifiers. Android automatic backup and
device transfer are disabled for Nia's private files and preferences. Reinstalling
after clearing app data therefore creates a new cloud identity.

The backend accepts only bounded ASCII letters, digits, underscores, and hyphens
for user, session, request, and Pub/Sub message identifiers before constructing
Firestore document paths.

The Android client treats the cloud layer as optional. Its health handshake
requires `service=project-nia-agent` and `api_version=1`; an arbitrary HTTP 200
response is not considered compatible. HTTP 401/403 is reported separately so
a private Cloud Run deployment is never mistaken for a generic network outage.
Offline recognition, local personalization, and already-approved avatar motion
remain available when the agent is absent.

Online calls are correlated end to end with a unique `request_id`. The Android
client accepts only the matching response and independently rechecks every
returned gloss against the signer-approved vocabulary included in that request.
One unknown gloss rejects the complete sequence; the client never removes the
unknown item and plays the remaining fragment. This duplicates the backend
allow-list intentionally so either side fails closed if the other is faulty.

# Gemma boundary

Gemma is intentionally not on the critical path.

The Android app first guarantees:

`camera → MediaPipe → V3 → frozen LiteRT model → confidence/personalization → isolated sign`

`OfflineLanguageLayer` is the extension point for a future LiteRT-LM Gemma implementation that can turn token sequences such as `PLEASE, WAIT` into natural text such as “Please wait a moment.” The default implementation is pass-through, so visual recognition remains offline and functional without a language model.

Before enabling Gemma, choose a model that fits the target phone's RAM/storage, add download/licensing UX, measure latency and thermal behavior on the actual device, and keep raw landmarks plus personal embeddings local by default.


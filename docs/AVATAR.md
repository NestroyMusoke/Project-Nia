# Nia 3D signing avatar

## What is implemented

The Android app now contains a native OpenGL ES 2.0 articulated avatar. It renders a shaded humanoid body, head, arms, and all 21 MediaPipe joints on each hand. Mouth opening, eyebrow position, eye opening, and head position are driven by the captured landmarks rather than decorative animation. Motion is produced from landmark coordinates, not video playback.

Each accepted isolated-sign recording can be retargeted into a 64-frame local `.niamotion` clip. The coordinates are normalized around the signer’s shoulders, resampled, and stored inside the app’s private data directory. The original camera image is not stored.

The **3D signer** button previews the latest motion. A fluent signer must watch the complete animation and explicitly approve it using **Signer-validate avatar motion**. Only approved clips are exposed to the online agent and permitted in generated replies.

## Safety contract

- Gemini chooses only from the exact signer-validated gloss list supplied by the phone.
- The backend filters the returned list again; unknown glosses are discarded.
- Draft clips never play as agent replies.
- Correcting a recognition discards the wrongly labelled draft before saving the corrected one.
- An empty gloss list is required when the available vocabulary cannot faithfully communicate the response.
- A gloss sequence is not automatically assumed to be grammatical ASL. Fluent-sign review remains required.

## Commissioning the 32-sign avatar vocabulary

For each supported sign, record a clean isolated performance with the upper body, hands, and face visible. Correct the recognized label if necessary, open **3D signer**, inspect the complete motion from the front, and approve it only after fluent-sign review. Repeat any clip with poor finger shape, occlusion, body position, timing, or facial information.

The frozen classifier is not retrained by this process. Avatar motion files are a separate presentation library and do not alter the final-test evidence.

## Current boundary

This milestone supplies an actual 3D rendering and retargeting engine, not a claim of complete ASL generation. The current vocabulary remains the frozen set of 32 isolated ASL signs. Natural continuous signing, non-manual grammar, coarticulation between clips, and support for other sign languages require additional signer-authored motion data and evaluation.

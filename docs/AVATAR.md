# Nia 3D signing avatar

## Avatar asset and renderer

The production renderer uses Google Filament and a complete VRM humanoid rig,
including individual finger bones. The bundled Alicia Solid model is credited
to DWANGO Co., Ltd. under its linked model terms; full attribution is in
`THIRD_PARTY_AVATAR.md`.

## What is implemented

The Android app loads a physically based, textured, finger-rigged humanoid rather
than drawing the former cylinder mannequin. Motion is produced from landmark
coordinates, not video playback.

Each accepted isolated-sign recording can be retargeted into a 64-frame local `.niamotion` clip. The coordinates are normalized around the signer’s shoulders, resampled, and stored inside the app’s private data directory. The original camera image is not stored.

The **3D signer** button previews the latest motion. A fluent signer must watch the complete animation and explicitly approve it using **Signer-validate avatar motion**. Only approved clips are exposed to the online agent and permitted in generated replies.

Packaged candidates can be commissioned without pretending they are replies:
type one exact gloss such as `hello`, tap **3D signer**, inspect the draft, and
use the validation action only when a fluent signer confirms it. The ordinary
**Sign it** path continues to reject that candidate until approval is recorded.

Approval records the reviewer's name, the sign language reviewed, the review
time, optional notes, and the exact SHA-256 digest of the motion file. Replacing
or changing that motion invalidates the approval automatically. A legacy motion
with only a boolean approval flag is treated as unapproved until it receives an
auditable review.

## Safety contract

- Gemini chooses only from the exact signer-validated gloss list supplied by the phone.
- The backend validates the returned list again. If any gloss is unavailable,
  the entire animation response is rejected rather than playing a misleading
  partial sentence.
- Draft clips never play as agent replies.
- Approval is valid only while its review digest matches the exact motion file.
- Correcting a recognition discards the wrongly labelled draft before saving the corrected one.
- An empty gloss list is required when the available vocabulary cannot faithfully communicate the response.
- A gloss sequence is not automatically assumed to be grammatical ASL. Fluent-sign review remains required.

## Commissioning the 32-sign avatar vocabulary

For each supported sign, record a clean isolated performance with the upper body, hands, and face visible. Correct the recognized label if necessary, open **3D signer**, inspect the complete motion from the front, and approve it only after fluent-sign review. Repeat any clip with poor finger shape, occlusion, body position, timing, or facial information.

The frozen classifier is not retrained by this process. Avatar motion files are a separate presentation library and do not alter the final-test evidence.

## Message planning and fingerspelling

Text is planned only against motion files that are actually available on the
device. A complete word motion is preferred. An unsupported word can fall back
to `fs_a` through `fs_z` motions only when every required letter motion is
present and signer-approved. Nia never drops an unsupported word and signs the
remaining fragment as though it were a complete translation.

Unvalidated training motion is never used as a text or agent reply, including
in development builds. Draft motion can be inspected only through the explicit
3D signer review workflow. Replies remain restricted to fluent-signer-approved
motion files.

## Current boundary

This milestone supplies an actual 3D rendering and retargeting engine, not a claim of complete ASL generation. The current vocabulary remains the frozen set of 32 isolated ASL signs. Natural continuous signing, non-manual grammar, coarticulation between clips, and support for other sign languages require additional signer-authored motion data and evaluation.

## Animation framework research basis

The playback architecture adapts model-independent ideas from Punchimudiyanse
and Meegama, *3D Animation framework for sign language* (2015): gestures are
stored separately from renderer code; multi-posture signs use ordered poses;
arm, palm, and finger channels are controlled independently; quaternions avoid
gimbal lock; and transitions start from the avatar's current posture rather
than assuming every sign begins in the same pose. Nia also establishes the
handshape early, holds the final posture for readability, and eases back to an
idle pose.

The paper demonstrates Sinhala Sign Language and therefore is not a source of
ASL gesture definitions. Nia's ASL motion coordinates must come from labelled
ASL sources and still require review by a fluent ASL signer.

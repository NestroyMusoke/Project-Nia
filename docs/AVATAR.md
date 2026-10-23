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

Before a newly captured draft is stored, a conservative stabilizer repairs a
single missing landmark frame only when tracked points exist immediately before
and after it. It also replaces an isolated spatial teleport only when the two
neighbouring frames closely agree and the middle point is a large outlier.
Continuous directional movement passes through unchanged. This reduces tracker
snapping; it does not infer a handshape, generate a sign, or establish linguistic
accuracy.

Every new draft reports separate tracking coverage for the left hand, right
hand, left arm, right arm, and face. The report is visible during preview and
inside the approval dialog. Low coverage produces review guidance, but the app
does not assume every sign is two-handed and does not convert coverage into a
claim of linguistic correctness. The fluent signer must still inspect handshape,
location, movement, orientation, timing, and non-manual information.

Motion format version 6 stores per-frame face-tracking presence for these
diagnostics. The reader remains compatible with version 5 motions, so existing
approved files are not rewritten and their review hashes stay valid. Historical
version 5 files cannot recover face-presence data and therefore show 0% facial
coverage until they are deliberately replaced.

The **3D signer** button previews the latest motion. A fluent signer must watch the complete animation and explicitly approve it using **Signer-validate avatar motion**. Only approved clips are exposed to the online agent and permitted in generated replies.

The rendered sequence can be replayed without another network request. Playback
offers very slow, slow, and relaxed presentation rates, with slow as the default.
Changing speed restarts the exact same motion sequence; it does not resample,
rewrite, approve, or linguistically modify any `.niamotion` clip.

The bottom panel continuously reports progress across the frozen 32-sign
vocabulary. **Review avatar motion library** opens an offline checklist in
which every item is visibly labelled `NOT RECORDED`, `DRAFT`, or `APPROVED`.
Selecting a missing sign begins a capture session fixed to that intended gloss;
this does not retrain the classifier or use its prediction as the label.
Selecting a draft allows the signer to preview it or record a replacement.
Selecting an approved motion offers preview or a separately confirmed revoke-
and-replace action. Revocation first rewrites the existing motion as an
unapproved draft and removes its review, then starts the labelled replacement
capture. If capture is cancelled, the old motion remains visible for review but
cannot be used in replies. The approval control is shown only for drafts.

Packaged candidates can be commissioned without pretending they are replies:
type one exact gloss such as `hello`, tap **3D signer**, inspect the draft, and
use the validation action only when a fluent signer confirms it. The ordinary
**Sign it** path continues to reject that candidate until approval is recorded.

Approval records the reviewer's name, the sign language reviewed, the review
time, optional notes, a structured confirmation checklist, and the exact SHA-256
digest of the motion file. The fluent signer must explicitly confirm handshape,
palm orientation and handedness, location and movement, timing and repetition,
and facial/non-manual information. The dialog is scrollable on a phone and will
not save approval until every item is checked. Replacing or changing the motion
invalidates approval automatically. A legacy motion with only a boolean approval
flag is treated as unapproved until it receives an auditable review.

Review format version 2 stores the structured checklist. Version 1 hash-bound
reviews remain readable and approved, but the preview labels them as legacy
reviews without a structured checklist; they are never silently upgraded.

## Approved-motion backup

**Backup or restore approved motions** uses Android's document picker, so Nia
does not request broad storage access. Export includes only motions whose current
bytes match an auditable signer review. Drafts are excluded.

Restore accepts only flat, size-limited `.niamotion` and `.niareview` pairs. It
rejects path traversal, duplicate or unknown entries, missing pairs, unapproved
motions, label mismatches, trailing binary data, and any review whose SHA-256
does not match its motion. Existing local motions are never overwritten. A
failed restore removes files newly created by that restore operation.

The archive contains reviewer names, sign languages, timestamps, and optional
notes. Treat it as sensitive project data and share it only with the reviewers'
permission.

## Safety contract

- Gemini chooses only from the exact signer-validated gloss list supplied by the phone.
- The backend validates the returned list again. If any gloss is unavailable,
  the entire animation response is rejected rather than playing a misleading
  partial sentence.
- Draft clips never play as agent replies.
- Approval is valid only while its review digest matches the exact motion file.
- Replay and speed controls operate only in the renderer and cannot change the approved motion bytes or review digest.
- Stabilization applies when a new draft is generated and cannot turn a draft into an approved motion.
- Replacement of an approved motion revokes approval before capture begins, so a cancelled or failed replacement cannot leave the old approval active.
- Tracking percentages are diagnostic evidence only and never approve a sign automatically.
- A new review is rejected unless every structured linguistic checklist item is explicitly confirmed.
- Correcting a recognition discards the wrongly labelled draft before saving the corrected one.
- An empty gloss list is required when the available vocabulary cannot faithfully communicate the response.
- A gloss sequence is not automatically assumed to be grammatical ASL. Fluent-sign review remains required.

## Commissioning the 32-sign avatar vocabulary

For each supported sign, open **Review avatar motion library**, choose a `NOT RECORDED` sign, and record a clean isolated performance with the upper body, hands, and face visible. The app quality-checks the capture and stores it as a draft under the selected label. Select the draft, inspect the complete motion from the front, and approve it only after fluent-sign review. Use **Record a replacement** for any clip with poor finger shape, occlusion, body position, timing, or facial information.

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

# Verification record

Verified on 2026-08-23:

- Backend Python source compiles.
- All frozen JSON files parse.
- Android manifest and resource XML parse.
- ADK/FastAPI dependencies install at the pinned versions.
- Backend tests: `4 passed` (structured output, safe fallback, avatar-gloss allow-list, and authenticated-push envelope handling in local mode).
- FastAPI `/healthz`: HTTP `200`, body `{"status":"ok"}`.
- Gradle wrapper: `8.14.3`, running successfully on Java `24.0.2`.
- Frozen classifier source and Android asset are byte-identical:
  `9bcc830589b8022d1012636b62a5b988fbe39c6be890438cfbb821c0a079c7f5`.
- Official Holistic Landmarker asset: 13,683,609 bytes, SHA-256
  `e2dab61191e2dcd0a15f943d8e3ed1dce13c82dfa597b9dd39f562975a50c3f8`.

Not verified on this machine:

- Android compilation, unit tests, APK installation, and live camera inference. Android Studio/SDK 36 are not installed, and the initial online Android dependency resolution was too slow to finish during this build session.
- Cloud deployment and a live Gemini call. No Google Cloud project or credentials were supplied.

The next acceptance checkpoint is therefore a physical Android test using a fresh field clip; it must not use the frozen final test set for threshold or model tuning.

Avatar implementation added after the original verification record:

- Native OpenGL ES renderer, landmark retargeter, private motion store, explicit signer-validation gate, and agent gloss allow-list are implemented.
- A new unit test covers fixed 64-frame avatar retargeting and verifies that captured motion defaults to unvalidated.
- A backend unit test covers removal of invented or unavailable glosses.
- These new Android tests and the live 3D appearance remain pending Android Studio/SDK 36 and a physical phone. No claim of signer validation or linguistic accuracy is made by the code alone.

## Motion commissioning verification, 2026-09-28

- Backend and motion-tool test suite: `8 passed`.
- Python sources compile and motion provenance parses as valid JSON.
- Two official PopSign `hello` training candidates were rejected for signing-
  hand coverage of 35.3% and 22.8%.
- A third signer candidate passed with 100% pose, 94.1% signing-hand, and 100%
  face landmark coverage.
- The passing candidate is packaged as an unvalidated draft. It is unavailable
  to text and agent reply paths until a fluent signer reviews the rendered 3D
  motion and records approval tied to the exact motion SHA-256.

## Offline review-library verification, 2026-09-28

- Added a phone-local inventory of all avatar motions with unambiguous Draft
  and Approved labels, counts, and direct selection for preview.
- Approved clips hide the approval action; draft clips remain unavailable to
  normal text and agent reply paths.
- The pure Kotlin presentation model compiles independently and its three JUnit
  tests pass.
- A complete Gradle rerun from this Codex environment was blocked by its local
  loopback restriction. Run `gradlew.bat testDebugUnitTest` in Windows CMD
  before committing this milestone.

## Guided 32-sign commissioning, 2026-09-29

- The on-device library now includes every frozen vocabulary gloss, including
  signs that have not yet been recorded.
- Missing signs start an explicitly labelled avatar-motion capture; the frozen
  classifier is not retrained, tuned, or used to relabel that commissioning
  recording.
- Drafts may be previewed or deliberately replaced. Approved clips are not
  exposed to the replacement action.
- The library presentation tests pass independently under JUnit 4.13.2. A full
  Gradle test remains required from Windows CMD before commit.

## Approved-motion backup, 2026-09-30

- Added Android document-picker export and restore without broad storage
  permission.
- Export is restricted to hash-verified signer-approved motions. Restore is
  size-limited, refuses to overwrite local motions, and verifies every review
  digest before writing.
- Restore rejects unsafe filenames, traversal, duplicates, and unmatched
  motion/review pairs. Nine pure Kotlin library and archive-policy tests pass.
- Physical export/restore and the complete Android Gradle suite remain the next
  device-side acceptance checkpoint.

## Guided personalization, 2026-09-30

- Added a visible 32-sign, three-samples-per-sign calibration checklist and
  `0/96` through `96/96` progress state.
- Guided capture stores only the frozen model's local 384-D embedding under the
  explicitly selected class. It does not modify classifier weights or avatar
  motion data.
- Partial calibration remains inactive. Resetting one sign removes its local
  examples and disables personalization until the complete support set is
  restored.
- Pure progress tests cover partial, complete, and per-sign display states. The
  Android Gradle suite and physical capture flow remain device-side checks.

## Online-agent compatibility status, 2026-09-30

- `/healthz` now identifies the Project Nia agent, API version, local or Vertex
  AI mode, and configured Gemini model without exposing credentials.
- Android distinguishes not configured, connected, authentication required,
  incompatible, and unreachable states while retaining offline operation.
- Three Kotlin status-presentation tests and the compiled Android agent client
  pass. The backend/tool suite now reports `10 passed`.

## Correlated fail-closed agent calls, 2026-09-30

- Android now requires the backend to echo the exact per-call request ID.
- The phone independently rejects the entire avatar sequence when even one
  returned gloss is not in its signer-approved vocabulary.
- Authentication, timeout, network, service, and invalid-response failures are
  represented separately instead of collapsing to a silent null response.
- Six Kotlin agent-policy/status tests pass, including unknown-gloss and stale-
  request rejection. Backend/tool tests remain `10 passed`.

## Android capture responsiveness, 2026-10-02

- Camera/avatar and controls now form a weighted split layout; the lower panel
  scrolls without pushing the visual signing stage off screen.
- ImageAnalysis closes idle frames without running MediaPipe. Landmark extraction
  occurs only while a recording is active.
- Conflicting controls are disabled during capture, stale frame-count updates
  are ignored, and backgrounding cancels the partial capture cleanly.
- Layout XML parses successfully. The final acceptance checkpoint remains the
  Android Gradle suite followed by visual testing on the Galaxy A17.

## Anonymous installation identity, 2026-10-02

- Replaced the shared Android cloud user ID with a random per-install UUID. No
  device serial, model, phone number, advertising ID, or account identifier is
  read or transmitted.
- Disabled Android cloud backup and device-transfer extraction for private app
  files, databases, preferences, and external app storage.
- API and Pub/Sub identifiers are constrained before any Firestore path is
  constructed; tests reject traversal-style identifiers.
- Identity-policy, Android-resource, backend, and complete Gradle checks remain
  part of the verification commands below.

## Multi-sign phrase buffer, 2026-10-03

- Accepted isolated signs now accumulate locally in a visible, bounded 20-token
  phrase instead of triggering one cloud call per capture.
- An uncertain capture is not added. **Correct and teach Nia** either replaces
  the latest accepted token or adds the explicit correction for an uncertain
  capture, and marks it as human-corrected in the agent request.
- Translation is an explicit user action. Offline mode speaks the literal gloss
  sequence; the compatible online agent receives the complete sequence and its
  interpretation is spoken aloud.
- Three pure Kotlin buffer tests and the compiled Android agent client pass.
  Backend tests report `9 passed`. The full Android Gradle suite remains the
  Windows CMD acceptance check because this sandbox blocks Gradle loopback.

## Avatar playback controls, 2026-10-04

- Added local replay for the most recently loaded draft or approved sequence;
  replay never invokes Gemini or changes the stored motion.
- Added bounded very-slow, slow, and relaxed renderer rates. Slow remains the
  default, and changing rate restarts the exact motion for consistent review.
- Playback rate is presentation state only. Approved `.niamotion` bytes and
  their hash-bound signer reviews remain unchanged.
- Two pure Kotlin speed-cycle tests pass, and Android resources compile with
  AAPT2. The complete Gradle suite and visual timing on the Galaxy A17 remain
  the physical-device acceptance checkpoint.

## Conservative avatar tracking stabilization, 2026-10-19

- Newly generated avatar drafts repair only one-frame landmark gaps bounded by
  valid neighbouring points.
- A joint is treated as a tracking spike only when neighbouring positions are
  close to each other and the middle position is a large spatial outlier.
  Sustained or consistently directional motion is preserved.
- Stabilization does not alter the classifier input, model weights, frozen test
  evidence, existing stored motions, or signer-approval policy.
- Retargeting and stabilization tests report `4 passed`, including gap repair,
  spike correction, directional-motion preservation, and metadata preservation.
  Visual acceptance still requires a fluent signer on the physical device.

## Approved-motion correction workflow, 2026-10-19

- Approved library entries now open a management action instead of being
  silently immutable.
- Replacement requires a dedicated confirmation. The store writes the current
  clip as unapproved before deleting its signer review and before starting a
  new capture, so interruption fails closed.
- A cancelled or failed replacement leaves an inspectable draft that is absent
  from local planning and the vocabulary sent to Gemini. The replacement must
  receive a new hash-bound fluent-signer review.
- The motion-store sources compile against Android SDK 36. Library-routing tests
  continue to distinguish missing, draft, and approved states; physical UI
  confirmation remains a Galaxy A17 acceptance check.

## Avatar tracking diagnostics, 2026-10-22

- Draft preview and signer approval now show independent coverage for both
  hands, both arms, and facial landmarks instead of one combined hand score.
- Guidance recommends re-recording when neither hand or arm is reliable, warns
  about limited face tracking, and asks the reviewer to confirm whether a low-
  coverage second hand is required rather than assuming every sign is two-handed.
- Motion format version 6 records face-tracking presence. Version 5 remains
  readable so no existing approved bytes or review hashes are migrated.
- Eleven pure avatar tests pass. The version-6 motion store and Filament renderer
  compile against Android SDK 36 and cached Filament 1.75.0 libraries.

## Structured fluent-signer review, 2026-10-22

- New signer approval requires explicit confirmation of handshape/fingers,
  orientation/handedness, location/movement, timing/repetition, and non-manual
  information in a scrollable phone dialog.
- Review format version 2 persists the checklist alongside reviewer identity,
  language, time, notes, and the exact motion digest. The store rejects an
  incomplete checklist.
- Version 1 reviews remain readable and hash-valid but are labelled as legacy;
  no historical review is silently upgraded to claim checks that were not made.
- The combined focused avatar suite reports `13 passed`, and the version-2
  review store compiles against Android SDK 36. Full UI acceptance remains on
  the Galaxy A17.

## Privacy-scoped session restoration, 2026-10-23

- Android saved-instance state restores the accepted pending sign phrase, its
  correction markers, the anonymous online session ID, and avatar playback
  speed after activity or process recreation.
- Raw frames, MediaPipe landmarks, 384-D embeddings, in-progress recording,
  avatar motion bytes, and signer-review content are never placed in the saved
  state. Interrupted recording continues to fail closed and must be repeated.
- Restore input is bounded to 20 tokens, uses the shortest parallel-array
  length, and drops malformed token entries. Unit coverage includes capacity,
  malformed state, and mismatched-array handling.

## Draft rejection workflow, 2026-10-23

- The motion library now offers confirmed discard for locally recorded,
  unapproved drafts without a packaged fallback. The action checks eligibility
  again before deleting the file.
- When the discarded draft is showing, the preview and signer-approval control
  are cleared. The library returns that gloss to Not Recorded.
- Replacement capture explicitly says that a successful capture overwrites the
  current draft. Fluent-signer visual review remains necessary on a phone.

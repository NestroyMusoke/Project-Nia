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

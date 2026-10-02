# Project Nia

Android-first, privacy-preserving assistance built around the frozen Project Nia visual-intent model.

## What is implemented

- CameraX front-camera capture of one isolated sign.
- Responsive split camera/control layout with a scrollable tool panel and recording-safe lifecycle handling.
- MediaPipe Holistic face, pose, and both-hand landmarks.
- Exact frozen V3 preprocessing to `64 × 272`.
- Local LiteRT inference with output discovery by tensor shape.
- Confidence and top-two-margin rejection instead of forced guesses.
- Persistent, local 384-D prototype personalization using the frozen 3-shot / `0.65` / temperature `12` protocol.
- Guided local personalization checklist for exactly three 384-D examples per sign, with per-sign progress and reset.
- Optional online client for a Gemini + Google ADK service.
- Visible, tap-to-refresh online-agent status with versioned backend compatibility and Cloud Run authentication detection.
- Correlated agent calls with explicit timeout/auth/service errors and phone-side fail-closed avatar-gloss validation.
- Native OpenGL 3D signing avatar with full upper-body and 21-joint-per-hand articulation; no video clips.
- Local MediaPipe-to-avatar motion retargeting, draft/validated states, and signer approval gating.
- Offline 32-sign commissioning checklist with Not Recorded/Draft/Approved status, labelled capture, replacement, and review preview.
- Storage-picker backup and restore for hash-verified approved motions and their signer-review records.
- Hearing-person message input that asks the online agent for playable, validated sign glosses.
- Cloud Run service with Firestore goal memory and Pub/Sub background-job tooling.
- Random per-install cloud identity with safe Firestore identifiers; no hardware or advertising identifier.
- A clean `OfflineLanguageLayer` boundary for Gemma; Gemma is not required for core recognition.

## Frozen claims — do not change

The files under `ml/frozen` are evidence, not training inputs.

- 32 isolated ASL signs.
- 2,425 final sequences from four signers unseen during training and model selection.
- Zero-shot top-1 accuracy: **86.47%**; macro F1: **86.06%**.
- Frozen complete 3-example-per-sign personalization: **92.11%** accuracy; **91.87%** macro F1 on the matched evaluation subset.
- Model: **3.88 MB FP16 TFLite**, SHA-256 `9bcc830589b8022d1012636b62a5b988fbe39c6be890438cfbb821c0a079c7f5`.

This is not a general-purpose or continuous sign-language translator. Partial incremental personalization and the confidence thresholds are product features, not part of the published final-test claim.

## Android setup

Install Android Studio with Android SDK 36 and a JDK 17-compatible toolchain, then open this directory as a Gradle project. The two required model assets are already bundled:

- `android/app/src/main/assets/project_nia_model3_mobile_fp16.tflite`
- `android/app/src/main/assets/holistic_landmarker.task`

Build from Windows PowerShell:

```powershell
.\gradlew.bat :android:app:testDebugUnitTest :android:app:assembleDebug
```

Install the APK from `android/app/build/outputs/apk/debug/app-debug.apk`, grant camera permission, tap **Start recording**, perform one isolated sign, then tap **Stop and recognize**. Use **Correct and teach Nia** after an ordinary capture, or **Personalize sign recognition** for the guided `3 × 32 = 96` sample checklist. Embeddings remain local, and partial calibration never changes inference.

The camera/avatar always retains its own portion of the screen while the lower
controls scroll independently. MediaPipe landmark inference runs only during an
active recording. Navigating away during capture cancels the partial recording
and keeps the selected commissioning or calibration target ready for a clean
retry.

Tap **Review avatar motion library** to see all 32 frozen vocabulary signs and whether each is not recorded, a draft, or approved. Selecting a missing sign starts a labelled capture session; selecting a draft allows preview or deliberate replacement. A fluent signer must inspect a draft before using **Signer-validate avatar motion**. Only validated clips are available to agent replies. A hearing person can type into **Message for the 3D signer** and tap **Sign it**; Gemini may return only glosses the phone has marked as validated. See `docs/AVATAR.md` for the commissioning and safety contract.

Use **Backup or restore approved motions** to preserve commissioned work with Android's file picker. Exports contain only approved motions and their exact hash-bound reviews. Restore rejects malformed or mismatched archives and never overwrites a local motion. Because review records contain signer identity and notes, keep backups secure and share them only with permission.

Android automatic cloud backup and device-to-device extraction are disabled for
Project Nia. Personal embeddings, local motion files, signer-review records, and
the anonymous installation ID therefore remain in app-private storage unless
the user explicitly exports an approved-motion archive. The cloud agent uses a
random `install-<UUID>` identity, never the device serial number, Samsung model,
phone number, advertising ID, or Google account.

To connect the online agent, add this to your user Gradle properties (do not commit it):

```properties
NIA_AGENT_BASE_URL=https://YOUR-CLOUD-RUN-URL
```

After rebuilding, the app checks `/healthz` for the exact Project Nia service
name and API version. It reports connected, not configured, authentication
required, incompatible, or unreachable without disabling offline recognition.
An authentication-required result is expected for a private Cloud Run service
until Firebase Authentication, Identity Platform, or an authenticated gateway
is placed in front of it. Do not embed a service-account credential in the APK.

Every online request carries a unique request ID, and the phone rejects a
response unless the ID is echoed back. If any returned avatar gloss is outside
the exact approved vocabulary sent with that request, the phone rejects the
whole response instead of animating a misleading partial sentence. Network,
timeout, authentication, server, and invalid-response failures are shown
separately while the accepted offline recognition result is retained.

## Agent setup

For local development, create a Python 3.11+ environment in `backend`, install `requirements.txt`, and set:

```powershell
$env:NIA_LOCAL_MODE="true"
$env:GOOGLE_API_KEY="YOUR_GEMINI_KEY"
uvicorn nia_agent.api:app --reload --port 8080
```

For Vertex AI on Google Cloud, authenticate with `gcloud`, then run from the project root:

```powershell
.\infra\deploy.ps1 -ProjectId "YOUR_PROJECT_ID" -Region "us-central1"
```

Pass `-AllowUnauthenticated` only for a controlled demo. A production mobile deployment should put Firebase Authentication/Identity Platform or an authenticated API gateway in front of Cloud Run.

## Verification boundaries

- The frozen Nia model in the Android assets and evidence folder has an identical SHA-256 hash.
- Kotlin unit tests cover V3 shape/finiteness/canonicalization and frozen personalization math.
- Python tests cover safe parsing of ADK structured output.
- The backend tests pass and `/healthz` returns HTTP 200 in local mode; see `docs/VERIFICATION.md`.
- This machine does not currently have the Android SDK or Gradle installed globally, so an APK cannot be compiled here until Android Studio/SDK 36 is installed. The included Gradle wrapper will fetch Gradle automatically.

Read `docs/ML_CONTRACT.md` before changing inference code and `docs/GEMMA.md` before adding an on-device language model.

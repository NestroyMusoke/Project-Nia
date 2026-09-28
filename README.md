# Project Nia

Android-first, privacy-preserving assistance built around the frozen Project Nia visual-intent model.

## What is implemented

- CameraX front-camera capture of one isolated sign.
- MediaPipe Holistic face, pose, and both-hand landmarks.
- Exact frozen V3 preprocessing to `64 × 272`.
- Local LiteRT inference with output discovery by tensor shape.
- Confidence and top-two-margin rejection instead of forced guesses.
- Persistent, local 384-D prototype personalization using the frozen 3-shot / `0.65` / temperature `12` protocol.
- Correction UI for teaching up to three examples per sign.
- Optional online client for a Gemini + Google ADK service.
- Native OpenGL 3D signing avatar with full upper-body and 21-joint-per-hand articulation; no video clips.
- Local MediaPipe-to-avatar motion retargeting, draft/validated states, and signer approval gating.
- Offline avatar motion library with explicit Draft/Approved status and one-tap review preview.
- Hearing-person message input that asks the online agent for playable, validated sign glosses.
- Cloud Run service with Firestore goal memory and Pub/Sub background-job tooling.
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

Install the APK from `android/app/build/outputs/apk/debug/app-debug.apk`, grant camera permission, tap **Start recording**, perform one isolated sign, then tap **Stop and recognize**. Use **Correct and teach Nia** to store the current 384-D embedding locally.

Tap **Review avatar motion library** to see every stored motion and whether it is a draft or approved. Select a motion to preview that exact animation. A fluent signer must inspect a draft before using **Signer-validate avatar motion**. Only validated clips are available to agent replies. A hearing person can type into **Message for the 3D signer** and tap **Sign it**; Gemini may return only glosses the phone has marked as validated. See `docs/AVATAR.md` for the commissioning and safety contract.

To connect the online agent, add this to your user Gradle properties (do not commit it):

```properties
NIA_AGENT_BASE_URL=https://YOUR-CLOUD-RUN-URL
```

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

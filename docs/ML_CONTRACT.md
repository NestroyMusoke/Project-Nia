# Frozen ML contract

`project_nia_model3_mobile_fp16.tflite` is immutable. Do not retrain it, replace it, tune thresholds against the final test set, or select any later model using the final test results.

## Input

One isolated sign becomes a float32 tensor with shape `1 × 64 × 272`:

| Slice | Count | Meaning |
|---|---:|---|
| `0:42` | 42 | left-hand body-relative XY |
| `42:84` | 42 | right-hand body-relative XY |
| `84:102` | 18 | pose XY at indices `0,11,12,13,14,15,16,23,24` |
| `102:142` | 40 | selected lip XY |
| `142:205` | 63 | left-hand wrist/palm-normalized XYZ |
| `205:268` | 63 | right-hand wrist/palm-normalized XYZ |
| `268:272` | 4 | left hand, right hand, pose, lips presence |

The transform crops the raw sequence to the first/last hand-present frame with two frames of leading and two frames of trailing context, enforces at least six frames, fills missing values by temporal interpolation, resamples coordinates linearly to 64 frames, resamples masks by nearest index using ties-to-even rounding, normalizes body XY by shoulder center/distance, and canonicalizes a left-dominant sequence when `leftMean > rightMean + 0.05`.

## Outputs

The app discovers outputs by shape, not array order:

- 32 probabilities in the label order saved in `ml/frozen/metadata.json`.
- a 384-dimensional personalization embedding.

## Frozen personalization

- exactly 3 examples per sign;
- L2-normalized mean prototype;
- cosine similarity × temperature `12`;
- softmax across 32 classes;
- `0.35 × general probabilities + 0.65 × prototype probabilities`.

The published personalized result applies only to the complete `3 × 32 = 96`
calibration protocol. The app may collect partial calibration memory, but it
does not blend that memory into predictions until the complete support set is
present. This prevents a partially represented prototype softmax from changing
the frozen classifier in a way the published evaluation never measured.

The Android **Personalize sign recognition** checklist exposes all 32 frozen
classes and the number of locally stored examples for each. Selecting an
incomplete class records one quality-gated isolated sign, runs the unchanged
V3 pipeline and frozen model, and stores only its normalized 384-D embedding
under the user-selected class. It does not save camera frames or tune model
weights, thresholds, preprocessing, or final-test claims.

A user can explicitly reset one class and re-record it. Resetting even one
class immediately disables personalization until that class again has exactly
three examples.

## Capture quality

Before preprocessing, the app requires at least 12 captured frames, a usable
hand in at least 60% of frames, and both shoulders in at least 70% of frames.
Failed captures produce actionable framing guidance and do not run inference.
These are operational camera checks and were not selected on the final test set.

## Confidence

The app's `0.55` top-probability and `0.12` top-two margin defaults are conservative product settings, not values selected on the final test set. Validate or calibrate them only with new field/DEV data.

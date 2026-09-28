# ASL avatar motion data

Project Nia's frozen classifier vocabulary overlaps the 250-sign PopSign ASL
v1.0 vocabulary. Avatar motions must be derived only from the separate PopSign
`game/train` split.
PopSign validation and test videos are prohibited inputs to the avatar-motion
pipeline. This separate motion corpus is not used to retrain, tune, or evaluate
the frozen classifier.

## Source and licence

- Dataset: PopSign ASL v1.0
- Publishers: Georgia Institute of Technology and Deaf Professional Arts Network
- Data card: https://signdata.cc.gatech.edu/view/datasets/popsign_v1_0/index.html
- Download guide: https://signdata.cc.gatech.edu/view/guides/downloading_popsign/index.html
- Licence: Creative Commons Attribution 4.0 International (CC BY 4.0)
- Licence text: https://creativecommons.org/licenses/by/4.0/

PopSign's authors remain the authors of the source recordings. Project Nia's
derived landmark/motion files must retain the source filename, split, URL, and
SHA-256 digest in `android/app/src/main/assets/avatar_motions/provenance.json`.

## Release gate

A source video having the expected label is not sufficient proof that the
retargeted avatar remains linguistically readable. Every packaged motion starts
as an unapproved candidate. It may be exposed as a development preview, but it
must not be presented as a reliable translation until a fluent ASL signer has
reviewed the avatar output and explicitly approved it.

Before packaging, the build tool also rejects candidates with fewer than 12
frames, duration outside 0.5 to 5 seconds, pose coverage below 75%, best-hand
coverage below 60%, or face coverage below 70%. These checks measure tracking
quality only. Passing them does not prove linguistic correctness.

The original bundled `hello` candidate was removed after this gate measured
only 35.3% signing-hand coverage. Its source and rejection evidence remain in
`provenance.json`; the failed `.niamotion` file is no longer shipped.

The replacement `hello` candidate comes from a different PopSign training
signer and passed the mechanical gate with 100% pose coverage, 94.1% signing-
hand coverage, and 100% face coverage. It remains a draft until a fluent ASL
signer reviews the rendered avatar and creates the hash-bound approval record.

## Batch commissioning

`tools/commission_avatar_vocabulary.py` automates candidate selection for a
small vocabulary. For each requested gloss it walks through independent
PopSign training examples, records failed candidates, stops at the first clip
that passes the mechanical gate, and packages that clip as an unvalidated
draft. Existing passing drafts are preserved unless `--force` is supplied.

The default batch is `hello please thankyou yes no wait`. Batch success means
only that MediaPipe tracked the source reliably. Every resulting animation must
still pass fluent-signer review on the rendered avatar.

The motion generator and renderer may be improved against training recordings.
The frozen TFLite classifier and its final evaluation artifacts must not be
retrained, tuned, or selected against the final test set.

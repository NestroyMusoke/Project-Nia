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

The motion generator and renderer may be improved against training recordings.
The frozen TFLite classifier and its final evaluation artifacts must not be
retrained, tuned, or selected against the final test set.
